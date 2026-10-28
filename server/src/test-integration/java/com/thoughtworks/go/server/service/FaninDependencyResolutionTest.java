/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.config.materials.Filter;
import com.thoughtworks.go.config.materials.PackageMaterial;
import com.thoughtworks.go.config.materials.PackageMaterialConfig;
import com.thoughtworks.go.config.materials.PluggableSCMMaterial;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.config.materials.git.GitMaterial;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.domain.MaterialRevision;
import com.thoughtworks.go.domain.MaterialRevisions;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.ModifiedAction;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.helper.MaterialsMother;
import com.thoughtworks.go.server.caching.GoCache;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.domain.PipelineTimeline;
import com.thoughtworks.go.server.materials.DependencyMaterialUpdateNotifier;
import com.thoughtworks.go.server.materials.MaterialChecker;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.dd.NoModificationsPresentForDependentMaterialException;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.util.GoConfigFileHelper;
import com.thoughtworks.go.util.SystemEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Date;

import static com.thoughtworks.go.config.CaseInsensitiveString.cis;
import static com.thoughtworks.go.util.SystemEnvironment.RESOLVE_FANIN_MAX_BACK_TRACK_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings({"unused", "UnusedAssignment"})
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
    "classpath:/applicationContext-global.xml",
    "classpath:/applicationContext-dataLocalAccess.xml",
    "classpath:/testPropertyConfigurer.xml",
    "classpath:/spring-all-servlet.xml",
})
public class FaninDependencyResolutionTest {
    @Autowired
    private DatabaseAccessHelper dbHelper;
    @Autowired
    private GoCache goCache;
    @Autowired
    private GoConfigDao goConfigDao;
    @Autowired
    private PipelineService pipelineService;
    @Autowired
    private MaterialRepository materialRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private GoConfigService goConfigService;
    @Autowired
    private SystemEnvironment systemEnvironment;
    @Autowired
    private MaterialChecker materialChecker;
    @Autowired
    private PipelineTimeline pipelineTimeline;
    @Autowired
    private DependencyMaterialUpdateNotifier notifier;

    private final GoConfigFileHelper configHelper = new GoConfigFileHelper();
    private ScheduleTestUtil u;

    @BeforeEach
    public void setUp() throws Exception {
        goCache.clear();
        configHelper.usingCruiseConfigDao(goConfigDao);
        configHelper.onSetUp();

        dbHelper.onSetUp();
        u = new ScheduleTestUtil(transactionTemplate, materialRepository, dbHelper, configHelper);
        notifier.disableUpdates();
    }

    @AfterEach
    public void teardown() throws Exception {
        notifier.enableUpdates();
        systemEnvironment.reset(RESOLVE_FANIN_MAX_BACK_TRACK_LIMIT);
        dbHelper.onTearDown();
        configHelper.onTearDown();
    }

    private void setMaxBackTrackLimit(@SuppressWarnings("SameParameterValue") int limit) {
        systemEnvironment.set(RESOLVE_FANIN_MAX_BACK_TRACK_LIMIT, limit);
    }

    private int maxBackTrackLimit() {
        return systemEnvironment.get(SystemEnvironment.RESOLVE_FANIN_MAX_BACK_TRACK_LIMIT);
    }

    @Test
    public void shouldRestoreMaterialNamesBasedOnMaterialConfig() {
        /*
            g -> up   -> down
                 +-> mid -+
         */

        GitMaterial git = u.wf(new GitMaterial("git"), "folder1");
        u.checkinInOrder(git, "g1");

        ScheduleTestUtil.AddedPipeline up = u.saveConfigWith("up", u.m(git));
        ScheduleTestUtil.MaterialDeclaration upForMid = u.m(up);
        ((DependencyMaterial) upForMid.material).setName(cis("up-for-mid"));
        ScheduleTestUtil.AddedPipeline mid = u.saveConfigWith("mid", upForMid);
        ScheduleTestUtil.MaterialDeclaration upForDown = u.m(up);
        ((DependencyMaterial) upForDown.material).setName(cis("up-for-down"));
        ScheduleTestUtil.AddedPipeline down = u.saveConfigWith("down", u.m(mid), upForDown);
        CruiseConfig cruiseConfig = goConfigDao.currentConfig();

        String up_1 = u.runAndPass(up, "g1");
        String mid_1 = u.runAndPass(mid, up_1);
        String down_1 = u.runAndPass(down, mid_1, up_1);

        MaterialRevisions given = u.mrs(
            u.mr(mid, false, mid_1),
            u.mr(up, false, up_1));

        MaterialRevisions revisionsBasedOnDependencies = getRevisionsBasedOnDependencies(down, cruiseConfig, given);

        for (MaterialRevision revisionsBasedOnDependency : revisionsBasedOnDependencies) {
            DependencyMaterial dependencyPipeline = (DependencyMaterial) revisionsBasedOnDependency.getMaterial();
            if (dependencyPipeline.getPipelineName().equals(cis("up"))) {
                assertThat(dependencyPipeline.getName()).isEqualTo(cis("up-for-down"));
            }
        }
        assertThat(revisionsBasedOnDependencies).isEqualTo(given);
    }

    @Test
    public void shouldTriggerCommonCIAndCDPhasesCorrectly_FAILING_SCN() {

        //     -----> Acceptance ----
        //     |        |           v
        //  Build       |          Staging ---> Production
        //     |        v           ^
        //     ----->Regression ----
        //
        GitMaterial git = u.wf(new GitMaterial("git"), "folder1");
        u.checkinInOrder(git, "g1", "g2", "g3");

        ScheduleTestUtil.AddedPipeline build = u.saveConfigWith("build", u.m(git));
        ScheduleTestUtil.AddedPipeline acceptance = u.saveConfigWith("acceptance", u.m(build));
        ScheduleTestUtil.AddedPipeline regression = u.saveConfigWith("regression", u.m(build), u.m(acceptance));
        ScheduleTestUtil.AddedPipeline staging = u.saveConfigWith("staging", u.m(acceptance), u.m(regression));
        ScheduleTestUtil.AddedPipeline production = u.saveConfigWith("production", u.m(staging));
        CruiseConfig cruiseConfig = goConfigDao.currentConfig();

        int i = 1;

        String b_1 = u.runAndPass(build, "g1");

        String a_1 = u.runAndPass(acceptance, b_1);
        String r_1 = u.runAndPass(regression, b_1, a_1);
        String s_1 = u.runAndPass(staging, a_1, r_1);
        String p_1 = u.runAndPass(production, s_1);

        String b_2 = u.runAndPass(build, "g2");

        MaterialRevisions given = u.mrs(
            u.mr(build, true, b_2),
            u.mr(acceptance, false, a_1));
        MaterialRevisions expected = u.mrs(
            u.mr(build, true, b_1),
            u.mr(acceptance, false, a_1));
        assertThat(getRevisionsBasedOnDependencies(regression, cruiseConfig, given)).isEqualTo(expected);

        String a_2 = u.runAndPass(acceptance, b_2);

        given = u.mrs(
            u.mr(build, true, b_2),
            u.mr(acceptance, false, a_2));
        expected = u.mrs(
            u.mr(build, true, b_2),
            u.mr(acceptance, true, a_2));
        assertThat(getRevisionsBasedOnDependencies(regression, cruiseConfig, given)).isEqualTo(expected);

        String r_2 = u.runAndPass(regression, b_2, a_2);
        String r_3 = u.runAndPass(regression, b_1, a_2);

        given = u.mrs(
            u.mr(acceptance, true, a_2),
            u.mr(regression, true, r_3));
        expected = u.mrs(
            u.mr(acceptance, true, a_2),
            u.mr(regression, true, r_2));
        assertThat(getRevisionsBasedOnDependencies(staging, cruiseConfig, given)).isEqualTo(expected);

        String s_2 = u.runAndPass(staging, a_2, r_2);
        String s_3 = u.runAndPass(staging, a_2, r_3);

        given = u.mrs(u.mr(staging, true, s_3));
        expected = u.mrs(u.mr(staging, true, s_2));

        assertThat(getRevisionsBasedOnDependencies(production, cruiseConfig, given)).isEqualTo(expected);

        String b_3 = u.runAndPass(build, "g3");
        String a_3 = u.runAndPass(acceptance, b_3);
        String r_4 = u.runAndPass(regression, b_3, a_3);
        String s_4 = u.runAndPass(staging, a_3, r_1);
        String s_5 = u.runAndPass(staging, a_1, r_4);

        given = u.mrs(
            u.mr(acceptance, true, a_3),
            u.mr(regression, true, r_4));
        expected = u.mrs(
            u.mr(acceptance, true, a_3),
            u.mr(regression, true, r_4));
        MaterialRevisions previousMaterialRevisions = u.mrs(
            u.mr(acceptance, false, a_1),
            u.mr(regression, false, r_4));
        assertThat(getRevisionsBasedOnDependencies(staging, cruiseConfig, given)).isEqualTo(expected);
//        assertThat(getBuildCause(staging,given,previousMaterialRevisions)).isNotNull(); //TODO: *************** Bug where pipeline should be triggered <Sara>

        String r_5 = u.runAndPass(regression, b_3, a_3);

        given = u.mrs(
            u.mr(acceptance, true, a_3),
            u.mr(regression, true, r_5));
        expected = u.mrs(
            u.mr(acceptance, true, a_3),
            u.mr(regression, true, r_5));
        assertThat(getRevisionsBasedOnDependencies(staging, cruiseConfig, given)).isEqualTo(expected);
        previousMaterialRevisions = u.mrs(
            u.mr(acceptance, false, a_1),
            u.mr(regression, false, r_4));
        assertThat(getBuildCause(staging, given, previousMaterialRevisions).getMaterialRevisions()).isEqualTo(expected);
    }

    @Test
    public void shouldReturnPreviousBuild_sRevisionsIfOneParentFailed() {
//            git ----------
//             |            |
//        ----- ------      |
//       |           |      |
//       v           v      |
//       P1         P2      |
//        |         |       |
//        |         |       |
//         --->P3<--        |
//             ^____________|

        GitMaterial gitMaterial = new GitMaterial("git-url");
        u.checkinInOrder(gitMaterial, "g1", "g2");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("P1", u.m(gitMaterial));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("P2", u.m(gitMaterial));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("P3", u.m(p1), u.m(p2), u.m(gitMaterial));

        String p1_1 = u.runAndPass(p1, "g1");
        String p2_1 = u.runAndPass(p2, "g1");
        String p3_1 = u.runAndPass(p3, p1_1, p2_1, "g1");

        String p1_2 = u.runAndPass(p1, "g2");
        String p2_2 = u.runAndFail(p2, "g2");

        MaterialRevisions given = u.mrs(
            u.mr(gitMaterial, true, "g2"),
            u.mr(p1, true, p1_2),
            u.mr(p2, true, p2_1));

        MaterialRevisions expected = u.mrs(
            u.mr(gitMaterial, true, "g1"),
            u.mr(p1, true, p1_1),
            u.mr(p2, true, p2_1));

        MaterialRevisions finalRevisions = getRevisionsBasedOnDependencies(p3, goConfigDao.currentConfig(), given);
        assertThat(finalRevisions).isEqualTo(expected);
    }

    @Test
    public void shouldResolveWithMultipleDiamondsOnSamePipelines() {
        /*
          |-------v
          |    /--P1--\
          git hg       P5
          |    \--P2--/
          +------^
         */
        HgMaterial hg = u.wf(new HgMaterial("hg", null), "folder1");
        String[] hg_revs = {"h11", "h12"};
        u.checkinInOrder(hg, hg_revs);

        GitMaterial git = u.wf(new GitMaterial("git"), "folder2");
        String[] git_revs = {"g11", "g12", "g13"};
        u.checkinInOrder(git, git_revs);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(hg), u.m(git));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(hg), u.m(git));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("p3", u.m(p1), u.m(p2));

        String p1_1 = u.runAndPass(p1, "h12", "g11");
        String p2_1 = u.runAndPass(p2, "h11", "g13");

        String p1_2 = u.runAndPass(p1, "h11", "g13");
        String p2_2 = u.runAndPass(p2, "h12", "g13");

        MaterialRevisions given = u.mrs(
            u.mr(p1, true, p1_2),
            u.mr(p2, true, p2_2));

        MaterialRevisions expected = u.mrs(
            u.mr(p1, true, p1_2),
            u.mr(p2, true, p2_1));

        assertThat(getRevisionsBasedOnDependencies(p3, goConfigDao.currentConfig(), given)).isEqualTo(expected);
    }

    @Test
    public void shouldFindCompatibleRevisionWhenDependencyMaterialHasMaterialName() {
        //      Third <- Second
        //         |     /
        //         |   /
        //         Last
        //

        SvnMaterial svn = u.wf(new SvnMaterial("svn", "username", "password", false), "folder1");
        String[] svn_revs = {"s1"};
        u.checkinInOrder(svn, svn_revs);

        ScheduleTestUtil.AddedPipeline second = u.saveConfigWith("second", u.m(svn));
        ScheduleTestUtil.AddedPipeline third = u.saveConfigWith("third", u.m(second, "two"));
        ScheduleTestUtil.AddedPipeline last = u.saveConfigWith("last", u.m(third, "three"), u.m(second, "two_2"));

        String second_1 = u.runAndPass(second, "s1");
        String third_1 = u.runAndPass(third, second_1);

        MaterialRevisions given = u.mrs(
            u.mr(third, true, third_1),
            u.mr(second, true, second_1));

        assertThat(getRevisionsBasedOnDependencies(last, goConfigDao.currentConfig(), given)).isEqualTo(given);
    }


    @Test
    public void shouldFindCompatibleRevisionWhenSameMaterialHasDiffFolderNamesInGraph() {
        //      Second <- Svn
        //         |     /
        //         |   /
        //         Third
        //

        SvnMaterial svn1 = u.wf(new SvnMaterial("svn", "username", "password", false), "one");
        SvnMaterial svn2 = u.wf(new SvnMaterial("svn", "username", "password", false), "two");
        String[] svn_revs = {"s1"};
        u.checkinInOrder(svn1, svn_revs);

        ScheduleTestUtil.AddedPipeline second = u.saveConfigWith("second", u.m(svn1));
        ScheduleTestUtil.AddedPipeline third = u.saveConfigWith("third", u.m(second), u.m(svn2));

        String second_1 = u.runAndPass(second, "s1");


        MaterialRevisions given = u.mrs(
            u.mr(second, true, second_1),
            u.mr(svn2, true, "s1")
        );

        MaterialRevisions materialRevisions = getRevisionsBasedOnDependencies(third, goConfigDao.currentConfig(), given);
        assertThat(materialRevisions).isEqualTo(given);
    }

    @Test
    public void shouldComputeRevisionCorrectlyWhenUpstreamPipelineHasModifications_ForDifferentStages() {
        /*
             /-->P1------\    p2(s1) --> p4
           git           P3
             \-->P2(S2)--/

        */
        GitMaterial git = u.wf(new GitMaterial("git"), "f");
        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(git));
        ScheduleTestUtil.AddedPipeline p2_s1 = u.saveConfigWith("p2", "s1", u.m(git));
        ScheduleTestUtil.AddedPipeline p2_s2 = u.addStageToPipeline(p2_s1.config.name(), "s2");
        ScheduleTestUtil.MaterialDeclaration p2_material = u.m(new DependencyMaterial(p2_s1.config.name(), cis("s2")));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("p3", p2_material, u.m(p1));
        ScheduleTestUtil.AddedPipeline p4 = u.saveConfigWith("p4", u.m(new DependencyMaterial(p2_s1.config.name(), cis("s1"))));

        u.checkinInOrder(git, "g1");
        String p1_1 = u.runAndPass(p1, "g1");
        String p2_s2_1 = u.runAndPass(p2_s2, "g1");
        MaterialRevisions given = u.mrs(
            u.mr(p1, true, p1_1),
            u.mr(p2_s2, true, p2_s2_1));

        MaterialRevisions revisionsBasedOnDependencies = getRevisionsBasedOnDependencies(p3, goConfigDao.currentConfig(), given);
        assertThat(revisionsBasedOnDependencies).isEqualTo(given);
    }

    @Test
    public void shouldPickTheRightRevisionsWhenMaterialIsRemovedAndPutBack() {

        GitMaterial git1 = u.wf(new GitMaterial("git1-url"), "git-folder1");
        GitMaterial git2 = u.wf(new GitMaterial("git2-url"), "git-folder2");
        GitMaterial git3 = u.wf(new GitMaterial("git3-url"), "git-folder3");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("P1", u.m(git1), u.m(git2));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("P2", u.m(git2));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("P3", u.m(p1), u.m(p2), u.m(git1));
        ScheduleTestUtil.AddedPipeline p4 = u.saveConfigWith("P4", u.m(p3), u.m(git2));

        u.checkinInOrder(git1, "git1_1");
        u.checkinInOrder(git2, "git2_1");


        String p1_1 = u.runAndPass(p1, "git1_1", "git2_1");
        String p2_1 = u.runAndPass(p2, "git2_1");
        String p3_1 = u.runAndPass(p3, p1_1, p2_1, "git1_1");
        String p4_1 = u.runAndPass(p4, p3_1, "git2_1");

        u.checkinInOrder(git1, "git1_2");
        u.checkinInOrder(git2, "git2_2");

        String p1_2 = u.runAndPass(p1, "git1_2", "git2_2");
        String p2_2 = u.runAndPass(p2, "git2_2");
        String p3_2 = u.runAndPass(p3, p1_2, p2_2, "git1_2");
        String p4_2 = u.runAndPass(p4, p3_2, "git2_2");

        configHelper.setMaterialConfigForPipeline("P2", git3.config());
        CruiseConfig cruiseConfig = goConfigDao.currentConfig();
        p2 = new ScheduleTestUtil.AddedPipeline(cruiseConfig.pipelineConfigByName(cis("P2")), p2.material);

        u.checkinInOrder(git1, "git1_3");
        u.checkinInOrder(git2, "git2_3");
        u.checkinInOrder(git3, "git3_1");
        String p1_3 = u.runAndPass(p1, "git1_3", "git2_3");
        String p2_3 = u.runAndPass(p2, "git3_1");
        String p3_3 = u.runAndPass(p3, p1_3, p2_3, "git1_3");

        //check wat happens to p4

        MaterialRevisions given = u.mrs(
            u.mr(git2, true, "git2_3"),
            u.mr(p3, true, p3_3));

        MaterialRevisions expected = u.mrs(
            u.mr(git2, true, "git2_3"),
            u.mr(p3, true, p3_3));

        MaterialRevisions finalRevisions = getRevisionsBasedOnDependencies(p4, cruiseConfig, given);
        assertThat(finalRevisions).isEqualTo(expected);

        //bring back git2 in p2

        configHelper.setMaterialConfigForPipeline("P2", git2.config());
        cruiseConfig = goConfigDao.currentConfig();
        p2 = new ScheduleTestUtil.AddedPipeline(cruiseConfig.pipelineConfigByName(cis("P2")), p2.material);

        //check wat happend to p4

        given = u.mrs(
            u.mr(git2, true, "git2_3"),
            u.mr(p3, true, p3_3));

        expected = u.mrs(
            u.mr(git2, true, "git2_3"),
            u.mr(p3, true, p3_3));

        finalRevisions = getRevisionsBasedOnDependencies(p4, cruiseConfig, given);
        assertThat(finalRevisions).isEqualTo(expected);
    }

    @Test
    public void shouldResolveDifferentCasesInAPipelineGraphSimilarToOneInGo01() {

        GitMaterial git1 = u.wf(new GitMaterial("git1-url"), "git-folder1");
        GitMaterial git2 = u.wf(new GitMaterial("git2-url"), "git-folder2");
        GitMaterial git3 = u.wf(new GitMaterial("git3-url"), "git-folder3");
        GitMaterial git4 = u.wf(new GitMaterial("git4-url"), "git-folder4");

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("P1", u.m(git1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("P2", u.m(p1), u.m(git1));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("P3", u.m(p1), u.m(git2));
        ScheduleTestUtil.AddedPipeline p4 = u.saveConfigWith("P4", u.m(p3), u.m(p2), u.m(git3));
        ScheduleTestUtil.AddedPipeline p5 = u.saveConfigWith("P5", u.m(p2), u.m(git3), u.m(git4));
        ScheduleTestUtil.AddedPipeline p6 = u.saveConfigWith("P6", u.m(p3), u.m(git3), u.m(git4));
        ScheduleTestUtil.AddedPipeline p7 = u.saveConfigWith("P7", u.m(p4), u.m(p5), u.m(p6));
        ScheduleTestUtil.AddedPipeline p8 = u.saveConfigWith("P8", u.m(p7));

    }

    @Test
    public void shouldThrowExceptionWhenStageHasPassedButIsNotPresentInModificationsTable() {
        GitMaterial git = u.wf(new GitMaterial("git1-url"), "git-folder1");
        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("P1", u.m(git));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("P2", u.m(git));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("P3", u.m(p1), u.m(p2));
        u.checkinInOrder(git, "g1", "g2");
        String p1_1 = u.runAndPass(p1, "g1");
        String p2_1 = u.runAndPass(p2, "g1");
        String p3_1 = u.runAndPass(p3, p1_1, p2_1);
        String p1_2 = u.runAndPass(p1, "g2");
        Pipeline p2_2instance = u.scheduleWith(p2, "g2");
        dbHelper.pass(p2_2instance);
        MaterialRevisions given = u.mrs(
            u.mr(p1, true, p1_2),
            u.mr(p2, false, p2_1)
        );
        MaterialRevisions previous = u.mrs(
            u.mr(p1, true, p1_1),
            u.mr(p2, true, p2_1)
        );
        assertThatThrownBy(() -> getBuildCause(p3, given, previous))
            .isInstanceOf(NoModificationsPresentForDependentMaterialException.class)
            .hasMessageContaining("No modifications found for " + p2_2instance.getFirstStage().getIdentifier().getStageLocator());
    }

    @Test
    public void shouldComputeRevisionCorrectlyWhen_MoreThan1UpstreamPipelineHasMinimumRevision() {
        /*       +----------
             /-->P1---\      v
           git------> P3 -> P4
             \-->P2--/       ^
                  +----------
        */
        GitMaterial git = u.wf(new GitMaterial("git"), "f");
        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(git));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(git));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("p3", u.m(p1), u.m(p2), u.m(git));
        ScheduleTestUtil.AddedPipeline p4 = u.saveConfigWith("p4", u.m(p1), u.m(p2), u.m(p3));

        int i = 0;
        u.checkinInOrder(git, u.d(i++), "g0");
        String p1_1 = u.runAndPass(p1, "g0");
        String p2_1 = u.runAndPass(p2, "g0");
        String p3_1 = u.runAndPass(p3, p1_1, p2_1, "g0");
        String p4_1 = u.runAndPass(p4, p1_1, p2_1, p3_1);

        u.checkinInOrder(git, u.d(i++), "g1");
        String p1_2 = u.runAndPass(p1, "g1");

        u.checkinInOrder(git, u.d(i++), "g2");
        String p2_2 = u.runAndPass(p2, "g2");
        String p3_2 = u.runAndPass(p3, p1_2, p2_2, "g2");

        u.checkinInOrder(git, u.d(i++), "g3");
        String p1_3 = u.runAndPass(p1, "g3");
        String p2_3 = u.runAndPass(p2, "g3");
        String p3_3 = u.runAndPass(p3, p1_3, p2_3, "g3");
        String p4_2 = u.runAndPass(p4, p1_3, p2_3, p3_3);

        String p2_4 = u.runAndPass(p2, "g1");

        String p3_4 = u.runAndPass(p3, p1_2, p2_4, "g1");

        MaterialRevisions given = u.mrs(
            u.mr(p1, true, p1_3),
            u.mr(p2, true, p2_4),
            u.mr(p3, true, p3_4));

        MaterialRevisions expected = u.mrs(
            u.mr(p1, true, p1_2),
            u.mr(p2, true, p2_4),
            u.mr(p3, true, p3_4));

        MaterialRevisions revisionsBasedOnDependencies = getRevisionsBasedOnDependencies(p4, goConfigDao.currentConfig(), given);
        assertThat(revisionsBasedOnDependencies).isEqualTo(expected);
    }

    @Test
    public void shouldResolveWithNoPassedBuildOfRootNode() {
        /*
          git -------+
           |         |
           |         |
           v         v
           P1 -----> P2
         */

        GitMaterial git = u.wf(new GitMaterial("git"), "folder1");
        String[] git_revs = {"g1"};
        int i = 0;
        u.checkinInOrder(git, u.d(i++), git_revs);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("P1", u.m(git));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("P2", u.m(p1), u.m(git));

        CruiseConfig cruiseConfig = goConfigDao.currentConfig();

        String p1_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "g1");

        setMaxBackTrackLimit(2);

        for (int j = 1; j <= maxBackTrackLimit() + 1; j++) {
            u.runAndFail(p2, u.d(i++), p1_1, "g1");
        }

        MaterialRevisions given = u.mrs(u.mr(p1, true, p1_1), u.mr(git, true, "g1"));

        MaterialRevisions finalRevisions = getRevisionsBasedOnDependencies(p2, cruiseConfig, given);
        assertThat(finalRevisions).isEqualTo(given);
    }

    @Test
    public void shouldResolveWithModifiedStageDefinitionOfRootNode() {
        /*
          git -------+
           |         |
           |         |
           v         v
           P1 -----> P2
         */

        GitMaterial git = u.wf(new GitMaterial("git"), "folder1");
        String[] git_revs = {"g1", "g2"};
        int i = 0;
        u.checkinInOrder(git, u.d(i++), git_revs);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("P1", u.m(git));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("P2", u.m(p1), u.m(git));

        String p1_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "g1");

        setMaxBackTrackLimit(2);

        for (int j = 1; j <= maxBackTrackLimit() + 1; j++) {
            u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), p1_1, "g1");
        }

        String p1_2 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "g2");

        p2 = u.changeStagenameForToPipeline("P2", "s", "new-stage");
        CruiseConfig cruiseConfig = goConfigDao.currentConfig();

        MaterialRevisions given = u.mrs(u.mr(p1, true, p1_2), u.mr(git, true, "g2"));

        MaterialRevisions finalRevisions = getRevisionsBasedOnDependencies(p2, cruiseConfig, given);
        assertThat(finalRevisions).isEqualTo(given);
    }

    @Test
    public void shouldConsiderFailedBuildOfRootNodeForFinalRevisionComputation() {
        /*
             +---> P1 ---+
             |           v
            git-------> P2
         */
        int i = 1;
        GitMaterial git1 = u.wf(new GitMaterial("git1"), "folder");
        String[] git_revs1 = {"g11", "g12", "g13"};
        u.checkinInOrder(git1, u.d(i++), git_revs1);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(git1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(git1), u.m(p1));

        CruiseConfig cruiseConfig = goConfigDao.currentConfig();

        String p1_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "g11");
        String p2_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), "g11", p1_1);

        String p1_2 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "g12");
        String p2_2 = u.runAndFail(p2, u.d(i++), "g12", p1_2);

        String p1_3 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "g13");

        MaterialRevisions given = u.mrs(
            u.mr(p1, true, p1_3),
            u.mr(git1, true, "g13"));

        assertThat(getRevisionsBasedOnDependencies(p2, cruiseConfig, given)).isEqualTo(given);
    }

    @Test
    public void shouldResolveWithModifiedMaterialDefinitionOfRoot() {
        /*
             +---> P1 ---+
             |           v
            git-------> P2 <---- hg
         */
        int i = 1;
        GitMaterial git1 = u.wf(new GitMaterial("git1"), "folder");
        String[] git_revs1 = {"g11"};
        u.checkinInOrder(git1, u.d(i++), git_revs1);

        HgMaterial hg = u.wf(new HgMaterial("hg", null), "folder1");
        String[] hg_revs1 = {"h11"};
        u.checkinInOrder(hg, u.d(i++), hg_revs1);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(git1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(git1), u.m(p1));

        String p1_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "g11");
        String p2_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), "g11", p1_1);
        p2 = u.addMaterialToPipeline(p2, u.m(hg));


        MaterialRevisions given = u.mrs(
            u.mr(git1, true, "g11"),
            u.mr(p1, true, p1_1),
            u.mr(hg, true, "h11"));

        assertThat(getRevisionsBasedOnDependencies(p2, goConfigDao.currentConfig(), given)).isEqualTo(given);
    }

    @Test
    public void shouldResolveTriangleDependencyWithPackageMaterial() {
        /*
            +---> P1 ---+
            |           v
           pkg1-------> P2
        */
        int i = 1;
        PackageMaterial pkg1 = MaterialsMother.packageMaterial();
        u.addPackageDefinition((PackageMaterialConfig) pkg1.config());
        String[] pkg_revs1 = {"pkg1-1", "pkg1-2"};
        u.checkinInOrder(pkg1, u.d(i++), pkg_revs1);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(pkg1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(pkg1), u.m(p1));

        String p1_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "pkg1-1");
        String p2_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), "pkg1-1", p1_1);

        MaterialRevisions given = u.mrs(
            u.mr(pkg1, true, "pkg1-2"),
            u.mr(p1, true, p1_1));

        MaterialRevisions expected = u.mrs(
            u.mr(pkg1, true, "pkg1-1"),
            u.mr(p1, true, p1_1));

        assertThat(getRevisionsBasedOnDependencies(p2, goConfigDao.currentConfig(), given)).isEqualTo(expected);
    }

    @Test
    public void shouldResolveDiamondDependencyWithPackageMaterial() {
        /*
            +---> P1 ---+
            |           v
           pkg1         P3
            |           ^
            +--> P2 ----+
        */
        int i = 1;
        PackageMaterial pkg1 = MaterialsMother.packageMaterial();
        u.addPackageDefinition((PackageMaterialConfig) pkg1.config());
        String[] pkg_revs = {"pkg1-1", "pkg1-2"};
        u.checkinInOrder(pkg1, u.d(i++), pkg_revs);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(pkg1));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(pkg1));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("p3", u.m(p1), u.m(p2));

        String p1_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "pkg1-1");
        String p2_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), "pkg1-1");
        String p2_2 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), "pkg1-2");

        MaterialRevisions given = u.mrs(
            u.mr(p1, true, p1_1),
            u.mr(p2, true, p2_2));

        MaterialRevisions expected = u.mrs(
            u.mr(p1, true, p1_1),
            u.mr(p2, true, p2_1));

        assertThat(getRevisionsBasedOnDependencies(p3, goConfigDao.currentConfig(), given)).isEqualTo(expected);
    }

    @Test
    public void shouldResolveDiamondDependencyWithPluggableSCMMaterial() {
        /*
            +---> P1 ---+
            |           v
           scm1         P3
            |           ^
            +--> P2 ----+
        */
        int i = 1;
        PluggableSCMMaterial pluggableSCMMaterial = MaterialsMother.pluggableSCMMaterial();
        u.addSCMConfig(pluggableSCMMaterial.getScmConfig());
        String[] pkg_revs = {"scm1-1", "scm1-2"};
        u.checkinInOrder(pluggableSCMMaterial, u.d(i++), pkg_revs);

        ScheduleTestUtil.AddedPipeline p1 = u.saveConfigWith("p1", u.m(pluggableSCMMaterial));
        ScheduleTestUtil.AddedPipeline p2 = u.saveConfigWith("p2", u.m(pluggableSCMMaterial));
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("p3", u.m(p1), u.m(p2));

        String p1_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p1, u.d(i++), "scm1-1");
        String p2_1 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), "scm1-1");
        String p2_2 = u.runAndPassWithGivenMDUTimestampAndRevisionStrings(p2, u.d(i++), "scm1-2");

        MaterialRevisions given = u.mrs(
            u.mr(p1, true, p1_1),
            u.mr(p2, true, p2_2));

        MaterialRevisions expected = u.mrs(
            u.mr(p1, true, p1_1),
            u.mr(p2, true, p2_1));

        assertThat(getRevisionsBasedOnDependencies(p3, goConfigDao.currentConfig(), given)).isEqualTo(expected);
    }

    @Test
    public void shouldResolveDiamondDependencyWithChildrenDependingOnDifferentStageDependency() {
        /*
               +---> P3 ---+
               |           v
        pkg -> P2          P5
               |           ^
               +--> P4 ----+
        */
        GitMaterial git = u.wf(new GitMaterial("git"), "f");
        u.checkinInOrder(git, "g1");

        ScheduleTestUtil.AddedPipeline p2_s1 = u.saveConfigWith("p2", "s1", u.m(git));
        ScheduleTestUtil.AddedPipeline p2_s2 = u.addStageToPipeline(p2_s1.config.name(), "s2");
        ScheduleTestUtil.AddedPipeline p3 = u.saveConfigWith("p3", u.m(new DependencyMaterial(p2_s1.config.name(), cis("s1"))));
        ScheduleTestUtil.AddedPipeline p4 = u.saveConfigWith("p4", u.m(new DependencyMaterial(p2_s1.config.name(), cis("s2"))));
        ScheduleTestUtil.AddedPipeline p5 = u.saveConfigWith("p5", u.m(p3), u.m(p4));

        String p2_s1_1 = u.runAndPass(p2_s1, "g1");
        String p2_s2_1 = u.runAndPass(p2_s2, "g1");
        String p3_1 = u.runAndPass(p3, p2_s1_1);
        String p4_1 = u.runAndPass(p4, p2_s2_1);

        MaterialRevisions given = u.mrs(
            u.mr(p3, true, p3_1),
            u.mr(p4, true, p4_1));

        MaterialRevisions revisionsBasedOnDependencies = getRevisionsBasedOnDependencies(p5, goConfigDao.currentConfig(), given);
        assertThat(revisionsBasedOnDependencies).isEqualTo(given);
    }

    private static final String UPSTREAM_DIR_FILTER = "upstream-dir/*,upstream-dir/**/*";
    private static final String DOWNSTREAM_DIR_FILTER = "downstream-dir/*,downstream-dir/**/*";

    // One git repository reaches the downstream pipeline directly (filtered) and through the upstream pipeline, which has
    // its own, different filter on the same repository:
    //
    //        +----[downstream's filter]---------------+
    //        |                                        v
    //  git --+                                   downstream
    //        |                                        ^
    //        +--> upstream --[dependency material]----+
    //
    // Returns the revision of the shared repository that fan-in resolves for the downstream pipeline after a new commit
    // ("g2") touching committedFile, when the upstream pipeline has run on "g1" only.
    private String revisionResolvedForDownstream(boolean ignoreForScheduling, String upstreamFilter, Filter downstreamFilter,
                                                 boolean downstreamFilterIsWhitelist, String committedFile) {
        GitMaterial gitForUpstream = u.wf(new GitMaterial("shared-git-url"), "f1");
        if (upstreamFilter != null) {
            gitForUpstream.setInvertFilter(true);
            gitForUpstream.setFilter(Filter.fromDisplayString(upstreamFilter));
        }
        u.checkinInOrder(gitForUpstream, "g1");
        ScheduleTestUtil.AddedPipeline upstream = u.saveConfigWith("upstream", u.m(gitForUpstream));
        String upstream1 = u.runAndPass(upstream, "g1");

        GitMaterial gitForDownstream = new GitMaterial("shared-git-url");
        gitForDownstream.setFolder("f2");
        gitForDownstream.setInvertFilter(downstreamFilterIsWhitelist);
        gitForDownstream.setFilter(downstreamFilter);
        DependencyMaterial dependency = new DependencyMaterial(upstream.material.getName(),
            upstream.material.getPipelineName(), upstream.material.getStageName(), ignoreForScheduling);
        ScheduleTestUtil.AddedPipeline downstream = u.saveConfigWith("downstream", u.m(gitForDownstream), u.m(dependency));
        u.runAndPass(downstream, "g1", upstream1);

        checkin("g2", committedFile, gitForDownstream);

        MaterialRevisions given = u.mrs(u.mr(dependency, false, upstream1), u.mr(gitForDownstream, true, "g2"));
        return getRevisionsBasedOnDependencies(downstream, goConfigDao.currentConfig(), given)
            .findRevisionFor(gitForDownstream).getLatestRevisionString();
    }

    private String revisionResolvedForDownstream(String upstreamFilter, String committedFile) {
        return revisionResolvedForDownstream(true, upstreamFilter, Filter.fromDisplayString(DOWNSTREAM_DIR_FILTER), true,
            committedFile);
    }

    // A pipeline with a whitelisted git material and an ignoreForScheduling dependency on a pipeline that watches the same
    // repository with a whitelist of its own. A commit that only the downstream's whitelist matches must schedule the
    // downstream: the upstream will never build it, so nothing would ever move a pin to the upstream's revision.
    @Test
    public void shouldScheduleDownstreamPipeline_withDiamondSharedFilteredMaterialAndIgnoredDependencyMaterial_whenFilteredMaterialHasMatchingChanges() {
        GitMaterial gitForUpstream = u.wf(new GitMaterial("shared-git-url"), "f1");
        gitForUpstream.setInvertFilter(true);
        gitForUpstream.setFilter(Filter.fromDisplayString(UPSTREAM_DIR_FILTER));
        u.checkinInOrder(gitForUpstream, "g1");
        ScheduleTestUtil.AddedPipeline upstream = u.saveConfigWith("upstream", u.m(gitForUpstream));
        String upstream1 = u.runAndPass(upstream, "g1");

        GitMaterial gitForDownstream = new GitMaterial("shared-git-url");
        gitForDownstream.setFolder("f2");
        gitForDownstream.setInvertFilter(true);
        gitForDownstream.setFilter(Filter.fromDisplayString(DOWNSTREAM_DIR_FILTER));
        DependencyMaterial ignoredDependency = new DependencyMaterial(upstream.material.getName(),
            upstream.material.getPipelineName(), upstream.material.getStageName(), true);
        ScheduleTestUtil.AddedPipeline downstream = u.saveConfigWith("downstream", u.m(gitForDownstream), u.m(ignoredDependency));
        u.runAndPass(downstream, "g1", upstream1);

        // ScheduleTestUtil.checkinFile uses the file's basename only, so it cannot represent a file inside a directory.
        // The filter matches on the repository-relative path, so the path is passed as the file name.
        checkin("g2", "downstream-dir/another_file.c", gitForDownstream);

        MaterialRevisions previous = u.mrs(u.mr(ignoredDependency, false, upstream1), u.mr(gitForDownstream, false, "g1"));
        MaterialRevisions given = u.mrs(u.mr(ignoredDependency, false, upstream1), u.mr(gitForDownstream, true, "g2"));

        assertThat(getBuildCause(downstream, given, previous))
            .as("a commit matching the downstream's whitelist must schedule it although the dependency did not advance")
            .isNotNull();
    }

    // The pin makes the downstream wait for the upstream, as it does for an ordinary dependency. It is kept only when the
    // upstream will itself build the newer commit.
    @Test
    public void shouldKeepThePinWhenIgnoredUpstreamWillBuildTheNewerCommit_soDownstreamWaitsForIt() {
        // the upstream takes the whole repository, so it will build the commit the downstream also matches
        assertThat(revisionResolvedForDownstream(null, "downstream-dir/another_file.c")).isEqualTo("g1");
    }

    @Test
    public void shouldKeepThePinWhenBothWhitelistsMatchTheNewerCommit() {
        assertThat(revisionResolvedForDownstream(UPSTREAM_DIR_FILTER + "," + DOWNSTREAM_DIR_FILTER,
            "downstream-dir/another_file.c")).isEqualTo("g1");
    }

    @Test
    public void shouldNotPinWhenIgnoredUpstreamWillNeverBuildTheNewerCommit_soDownstreamIsNotFrozen() {
        // the upstream's whitelist ignores downstream-dir/, so nothing would ever move the pin
        assertThat(revisionResolvedForDownstream(UPSTREAM_DIR_FILTER, "downstream-dir/another_file.c")).isEqualTo("g2");
    }

    @Test
    public void shouldKeepThePinForACommitOnlyTheUpstreamBuilds_soTheDownstreamStaysOnTheUpstreamsRevision() {
        assertThat(revisionResolvedForDownstream(UPSTREAM_DIR_FILTER, "upstream-dir/zone")).isEqualTo("g1");
    }

    @Test
    public void shouldKeepThePin_whenTheDependencyIsNotIgnoredForScheduling() {
        // an ordinary dependency pins the downstream to the upstream's revision whatever the upstream's filter says
        assertThat(revisionResolvedForDownstream(false, UPSTREAM_DIR_FILTER, Filter.fromDisplayString(DOWNSTREAM_DIR_FILTER),
            true, "downstream-dir/another_file.c")).isEqualTo("g1");
    }

    @Test
    public void shouldNotPinWhenTheDownstreamFilterIsABlacklist_andTheUpstreamWillNeverBuildTheNewerCommit() {
        // a commit outside the downstream's blacklist is one it builds; the upstream's whitelist ignores it
        assertThat(revisionResolvedForDownstream(true, UPSTREAM_DIR_FILTER, Filter.fromDisplayString("docs/*,docs/**/*"),
            false, "src/main.c")).isEqualTo("g2");
    }

    // A chain of three pipelines on one repository, each dependency ignoreForScheduling: a (a-dir/) <- b (b-dir/) <- c (c-dir/).
    // b has built a commit of its own (g2) that a has not. A new commit under c-dir/ (g3) is ignored by b's whitelist, so b
    // will never build it: c must be resolved to g3, not pinned to the older revision a's run embeds.
    @Test
    public void shouldNotPinThirdLevelPipelineToAnOlderRevision_whenTheMiddlePipelineWillNeverBuildTheNewCommit() {
        GitMaterial gitForA = u.wf(new GitMaterial("shared-git-url"), "fa");
        gitForA.setInvertFilter(true);
        gitForA.setFilter(Filter.fromDisplayString("a-dir/*,a-dir/**/*"));
        u.checkinInOrder(gitForA, "g1");
        ScheduleTestUtil.AddedPipeline pipelineA = u.saveConfigWith("pipeline_a", u.m(gitForA));
        String a1 = u.runAndPass(pipelineA, "g1");

        GitMaterial gitForB = new GitMaterial("shared-git-url");
        gitForB.setFolder("fb");
        gitForB.setInvertFilter(true);
        gitForB.setFilter(Filter.fromDisplayString("b-dir/*,b-dir/**/*"));
        DependencyMaterial ignoredADependency = new DependencyMaterial(pipelineA.material.getName(),
            pipelineA.material.getPipelineName(), pipelineA.material.getStageName(), true);
        ScheduleTestUtil.AddedPipeline pipelineB = u.saveConfigWith("pipeline_b", u.m(gitForB), u.m(ignoredADependency));
        String b1 = u.runAndPass(pipelineB, "g1", a1);

        GitMaterial gitForC = new GitMaterial("shared-git-url");
        gitForC.setFolder("fc");
        gitForC.setInvertFilter(true);
        gitForC.setFilter(Filter.fromDisplayString("c-dir/*,c-dir/**/*"));
        DependencyMaterial ignoredBDependency = new DependencyMaterial(pipelineB.material.getName(),
            pipelineB.material.getPipelineName(), pipelineB.material.getStageName(), true);
        ScheduleTestUtil.AddedPipeline pipelineC = u.saveConfigWith("pipeline_c", u.m(gitForC), u.m(ignoredBDependency));
        u.runAndPass(pipelineC, "g1", b1);

        checkin("g2", "b-dir/changed.c", gitForB);
        String b2 = u.runAndPass(pipelineB, "g2", a1);
        checkin("g3", "c-dir/changed.c", gitForC);

        MaterialRevisions given = u.mrs(u.mr(ignoredBDependency, false, b2), u.mr(gitForC, true, "g3"));
        assertThat(getRevisionsBasedOnDependencies(pipelineC, goConfigDao.currentConfig(), given)
            .findRevisionFor(gitForC).getLatestRevisionString()).isEqualTo("g3");
    }

    private void checkin(String revision, String file, GitMaterial material) {
        transactionTemplate.execute(status -> {
            Modification modification = new Modification("user", "comment", "a@b.com", new Date(), revision);
            modification.createModifiedFile(file, null, ModifiedAction.added);
            materialRepository.saveMaterialRevision(new MaterialRevision(material, modification));
            return null;
        });
    }

    private BuildCause getBuildCause(ScheduleTestUtil.AddedPipeline staging, MaterialRevisions given, MaterialRevisions previous) {
        AutoBuild autoBuild = new AutoBuild(goConfigService, pipelineService, staging.config.name().toString(), systemEnvironment, materialChecker);
        pipelineTimeline.update();
        return autoBuild.onModifications(given, false, previous);
    }

    private MaterialRevisions getRevisionsBasedOnDependencies(ScheduleTestUtil.AddedPipeline pipeline, CruiseConfig cruiseConfig, MaterialRevisions given) {
        pipelineTimeline.update();
        return pipelineService.getRevisionsBasedOnDependencies(given, cruiseConfig, pipeline.config.name());
    }

}
