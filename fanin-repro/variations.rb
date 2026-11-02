#!/usr/bin/env ruby
# frozen_string_literal: true

#
# Copyright Thoughtworks, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# The fan-in variations: pipelines A, B and C chained by dependency materials, with the git repositories separate or partly
# shared, and B and C monitoring a file in A's repository.
#
# Each variation is data: its repositories, its three pipelines (git materials with whitelists, dependency materials), and a
# list of steps. A step commits to some files, then says which pipelines are EXPECTED to schedule on a stock server, on a
# server with the first patch, and on one with the refined patch. The expectations are written down here before any run, so a
# run reports PASS (it matched) or DIFF (a finding), never an after-the-fact story.
#
#   ./variations.rb run V2 stock       one variation against one server image
#   ./variations.rb run V2 refined
#   ./variations.rb all                every variation x {stock, patched, refined} in parallel
#   ./variations.rb doc                the Markdown report, generated from the data below and the recorded results
#
# Needs: docker, git, ruby. Evidence (config, every history answer, results.json, run.log) lands in ./out/variations/.
# Images: STOCK_IMAGE / PATCHED_IMAGE / REFINED_IMAGE / AGENT_IMAGE environment variables override the defaults below.

require 'fileutils'
require 'json'
require 'net/http'
require 'open3'

HERE = File.expand_path(__dir__)
STOCK_IMAGE = ENV.fetch('STOCK_IMAGE', 'gocd/gocd-server:v26.1.0')
PATCHED_IMAGE = ENV.fetch('PATCHED_IMAGE', 'gocd-server:first-patch') # the first patch: an ignored dependency never pins
REFINED_IMAGE = ENV.fetch('REFINED_IMAGE', 'gocd-server:refined-fanin') # the pin is kept when the upstream will build the commit
AGENT_IMAGE = ENV.fetch('AGENT_IMAGE', 'gocd/gocd-agent-ubuntu-24.04:v26.1.0')
POLL_WAIT = Integer(ENV.fetch('POLL_WAIT', '180')) # seconds to wait for an expected run (GoCD polls materials about every minute)
SETTLE = Integer(ENV.fetch('SETTLE', '75')) # after every expected run exists, how long to watch for an unexpected one
TOGETHER = 10 # runs scheduled within this many seconds of each other started together

A = 'a-deploy'
B = 'b-deploy'
C = 'c-deploy'
ALL = [A, B, C].freeze

# A git material on repository <repo>, whitelisted to <includes> (nil: the whole repository).
def git(name, repo, includes = nil, dest = nil)
  { kind: 'git', name: name, repo: repo, includes: includes, dest: dest }
end

def dep(name, upstream, ignore)
  { kind: 'dep', name: name, upstream: upstream, ignore: ignore }
end

# `refined` defaults to `patched`; `ordered` is the acceptance rule for the refined build: the pipelines that scheduled must
# NOT have started together (a downstream waits for its upstream's run of the same commit).
def step(say, touches, stock, patched, refined = nil, ordered: false)
  { say: say, touches: touches, stock: stock, patched: patched, refined: refined.nil? ? patched : refined, ordered: ordered }
end

VARIATIONS = {
  'V1' => {
    title: 'Three separate repositories, A to B to C by dependency',
    repos: { 'ra' => ['a/f'], 'rb' => ['b/f'], 'rc' => ['c/f'] },
    pipelines: {
      A => [git('repo', 'ra', ['a/**/*'])],
      B => [git('repo', 'rb', ['b/**/*']), dep('up', A, true)],
      C => [git('repo', 'rc', ['c/**/*']), dep('up', B, true)]
    },
    steps: [
      step('initial commits: every pipeline has its first run', [], ALL, ALL),
      step("C's own repository changes", [%w[rc c/f]], [C], [C]),
      step("B's own repository changes", [%w[rb b/f]], [B], [B]),
      step("A's repository changes (dependencies are ignored for scheduling, so B and C stay quiet)", [%w[ra a/f]], [A], [A]),
      step('all three repositories change', [%w[ra a/f], %w[rb b/f], %w[rc c/f]], ALL, ALL)
    ]
  },
  'V2' => {
    title: "Separate repositories, B and C also monitor a file in A's repository",
    repos: { 'ra' => ['a/f', 'shared/b.yml', 'shared/c.yml'], 'rb' => ['b/f'], 'rc' => ['c/f'] },
    pipelines: {
      A => [git('repo', 'ra', ['a/**/*'])],
      B => [git('own', 'rb', ['b/**/*'], 'own'), git('a-file', 'ra', ['shared/b.yml'], 'a-file'), dep('up', A, true)],
      C => [git('own', 'rc', ['c/**/*'], 'own'), git('a-file', 'ra', ['shared/c.yml'], 'a-file'), dep('up', B, true)]
    },
    steps: [
      step('initial commits', [], ALL, ALL),
      step("only the file B monitors in A's repository changes (A's whitelist does not match)", [%w[ra shared/b.yml]], [], [B]),
      step("only the file C monitors in A's repository changes", [%w[ra shared/c.yml]], [], [C]),
      step("B's own repository changes", [%w[rb b/f]], [B], [B]),
      step("A's own directory changes: B and C follow it (B and C share its repository; measured on stock, patched and refined)",
           [%w[ra a/f]], ALL, ALL),
      step("A's directory and both monitored files change in one commit",
           [%w[ra a/f], %w[ra shared/b.yml], %w[ra shared/c.yml]], ALL, ALL, ordered: true)
    ]
  },
  'V5' => {
    title: "V2's repositories and whitelists with NO dependency materials: is the upstream's run what drags B and C along?",
    repos: { 'ra' => ['a/f', 'shared/b.yml', 'shared/c.yml'], 'rb' => ['b/f'], 'rc' => ['c/f'] },
    pipelines: {
      A => [git('repo', 'ra', ['a/**/*'])],
      B => [git('own', 'rb', ['b/**/*'], 'own'), git('a-file', 'ra', ['shared/b.yml'], 'a-file')],
      C => [git('own', 'rc', ['c/**/*'], 'own'), git('a-file', 'ra', ['shared/c.yml'], 'a-file')]
    },
    steps: [
      step('initial commits', [], ALL, ALL),
      step("only A's own directory changes (nothing B or C watches changes)", [%w[ra a/f]], [A], [A]),
      step('only the file B monitors changes', [%w[ra shared/b.yml]], [B], [B]),
      step("A's own directory changes again, after B has built", [%w[ra a/f]], [A], [A])
    ]
  },
  'V6' => {
    title: "V2's repositories with only B depending on A (C has no dependency)",
    repos: { 'ra' => ['a/f', 'shared/b.yml', 'shared/c.yml'], 'rb' => ['b/f'], 'rc' => ['c/f'] },
    pipelines: {
      A => [git('repo', 'ra', ['a/**/*'])],
      B => [git('own', 'rb', ['b/**/*'], 'own'), git('a-file', 'ra', ['shared/b.yml'], 'a-file'), dep('up', A, true)],
      C => [git('own', 'rc', ['c/**/*'], 'own'), git('a-file', 'ra', ['shared/c.yml'], 'a-file')]
    },
    steps: [
      step('initial commits', [], ALL, ALL),
      # Measured on all three servers: a downstream that shares the upstream's repository follows the upstream's run even
      # though its own whitelist does not match the commit (stock behaviour, not the patch's).
      step("A's own directory changes: B, sharing the repository, follows A's run; C, with no dependency, does not",
           [%w[ra a/f]], [A, B], [A, B]),
      step('only the file B monitors changes', [%w[ra shared/b.yml]], [], [B]),
      step("A's own directory changes again, after B has built: B follows again", [%w[ra a/f]], [A, B], [A, B])
    ]
  },
  'V3' => {
    title: 'A and B share a repository, C is separate and monitors a file in the shared one',
    repos: { 'rab' => ['a/f', 'b/f', 'shared/c.yml'], 'rc' => ['c/f'] },
    pipelines: {
      A => [git('repo', 'rab', ['a/**/*'])],
      B => [git('repo', 'rab', ['b/**/*']), dep('up', A, true)],
      C => [git('own', 'rc', ['c/**/*'], 'own'), git('a-file', 'rab', ['shared/c.yml'], 'a-file'), dep('up', B, true)]
    },
    steps: [
      step('initial commits', [], ALL, ALL),
      step("only B's directory changes in the shared repository (the shape of the original freeze)", [%w[rab b/f]], [], [B]),
      step('only the file C monitors changes', [%w[rab shared/c.yml]], [], [C]),
      step("C's own repository changes", [%w[rc c/f]], [C], [C]),
      step("A's directory changes: B and C follow it (measured on stock, patched and refined)", [%w[rab a/f]], ALL, ALL),
      step("A's and B's directories and the monitored file change together",
           [%w[rab a/f], %w[rab b/f], %w[rab shared/c.yml]], ALL, ALL, ordered: true)
    ]
  },
  'V4' => {
    title: 'One repository, ordinary dependencies (not ignored for scheduling): the control',
    repos: { 'app' => ['a/f', 'b/f', 'c/f', 'shared.yml'] },
    pipelines: {
      A => [git('repo', 'app', ['a/**/*', 'shared.yml'])],
      B => [git('repo', 'app', ['b/**/*', 'shared.yml']), dep('up', A, false)],
      C => [git('repo', 'app', ['c/**/*', 'shared.yml']), dep('up', B, false)]
    },
    steps: [
      step('initial commit touches all three whitelists', [], ALL, ALL),
      step("only C's directory changes (an ordinary dependency still pins C to B's revision)", [%w[app c/f]], [], []),
      step("only A's directory changes: A passes, which triggers B, which triggers C", [%w[app a/f]], ALL, ALL),
      step('the shared file changes: all three whitelists match', [%w[app shared.yml]], ALL, ALL)
    ]
  },
  'V7' => {
    title: 'One repository, three whitelists, ignored dependencies: the originally reported shape',
    repos: { 'app' => ['a/f', 'b/f', 'c/f', 'shared.yml'] },
    pipelines: {
      A => [git('repo', 'app', ['a/**/*', 'shared.yml'])],
      B => [git('repo', 'app', ['b/**/*', 'shared.yml']), dep('up', A, true)],
      C => [git('repo', 'app', ['c/**/*', 'shared.yml']), dep('up', B, true)]
    },
    steps: [
      step('initial commit touches all three whitelists', [], ALL, ALL),
      step("only C's directory changes (A and B will never build this commit)", [%w[app c/f]], [], [C]),
      step("only B's directory changes", [%w[app b/f]], [], [B]),
      step("only A's directory changes: B and C follow A's run", [%w[app a/f]], ALL, ALL),
      step('the shared file changes: all three whitelists match, so the dependency order should hold',
           [%w[app shared.yml]], ALL, ALL, ordered: true)
    ]
  }
}.freeze

# One variation against one server image.
class Run
  attr_reader :out

  def initialize(variation, profile, port)
    @id = variation
    @v = VARIATIONS.fetch(variation)
    @profile = profile
    @port = port
    @name = "fanin-#{variation.downcase}-#{profile}-#{Process.pid}"
    @image = { 'stock' => STOCK_IMAGE, 'patched' => PATCHED_IMAGE, 'refined' => REFINED_IMAGE }.fetch(profile)
    @out = File.join(HERE, 'out', 'variations', "#{Time.now.strftime('%Y%m%dT%H%M%S')}-#{variation}-#{profile}")
    @work = File.join(HERE, 'work', "#{variation}-#{profile}")
    @api = "http://localhost:#{port}/go/api"
    FileUtils.mkdir_p(@out)
    @results = []
  end

  def log(text)
    line = "#{Time.now.strftime('%H:%M:%S')}  [#{@id} #{@profile}] #{text}"
    puts line
    $stdout.flush
    File.open(File.join(@out, 'run.log'), 'a') { |f| f.puts(line) }
  end

  def sh(*argv, cwd: nil, check: true)
    stdout, status = Open3.capture2e(*argv, **(cwd ? { chdir: cwd } : {}))
    raise "#{argv.join(' ')} failed: #{stdout}" if check && !status.success?

    stdout.strip
  end

  # ---- repositories ---------------------------------------------------------------------------------------------------
  def commit(repo, files, message)
    path = File.join(@work, repo)
    files.each do |rel|
      target = File.join(path, rel)
      FileUtils.mkdir_p(File.dirname(target))
      File.open(target, 'a') { |f| f.puts(Process.clock_gettime(Process::CLOCK_REALTIME, :nanosecond)) }
    end
    sh('git', 'add', '-A', cwd: path)
    sh('git', '-c', 'user.name=repro', '-c', 'user.email=repro@example.com', 'commit', '-qm', message, cwd: path)
    sh('chmod', '-R', 'a+rwX', path)
    sh('git', 'rev-parse', '--short', 'HEAD', cwd: path)
  end

  def material_config(material, gits)
    return { 'pipeline' => material[:upstream], 'stage' => 'deploy', 'ignore_for_scheduling' => material[:ignore] } if material[:kind] == 'dep'

    mat = { 'git' => "/repos/#{material[:repo]}", 'branch' => 'main' }
    mat['includes'] = material[:includes] if material[:includes]
    mat['destination'] = material[:dest] if gits.size > 1
    mat
  end

  def make_repos
    FileUtils.rm_rf(@work)
    FileUtils.mkdir_p(File.join(@work, 'config'))
    @v[:repos].each do |repo, files|
      FileUtils.mkdir_p(File.join(@work, repo))
      sh('git', 'init', '-q', '-b', 'main', cwd: File.join(@work, repo))
      commit(repo, files, 'initial')
    end
    pipelines = @v[:pipelines].to_h do |name, materials|
      gits = materials.select { |m| m[:kind] == 'git' }
      mats = materials.to_h { |m| [m[:name], material_config(m, gits)] }
      [name, { 'group' => 'repro', 'materials' => mats,
               'stages' => [{ 'deploy' => { 'jobs' => { 'run' => { 'tasks' => [{ 'exec' => { 'command' => 'echo', 'arguments' => [name] } }] } } } }] }]
    end
    config = JSON.pretty_generate({ 'format_version' => 10, 'pipelines' => pipelines }) # JSON is YAML
    File.write(File.join(@work, 'config', 'pipelines.gocd.yaml'), config)
    File.write(File.join(@out, 'pipelines.gocd.yaml'), config)
    sh('git', 'init', '-q', '-b', 'main', cwd: File.join(@work, 'config'))
    sh('git', 'add', '-A', cwd: File.join(@work, 'config'))
    sh('git', '-c', 'user.name=repro', '-c', 'user.email=repro@example.com', 'commit', '-qm', 'pipelines',
       cwd: File.join(@work, 'config'))
    sh('chmod', '-R', 'a+rwX', @work)
  end

  # ---- GoCD -----------------------------------------------------------------------------------------------------------
  def get(path, accept = 'application/vnd.go.cd.v1+json')
    uri = URI("#{@api}#{path}")
    request = Net::HTTP::Get.new(uri, 'Accept' => accept)
    response = Net::HTTP.start(uri.host, uri.port, read_timeout: 30, open_timeout: 30) { |http| http.request(request) }
    raise "GET #{path}: #{response.code}" unless response.is_a?(Net::HTTPSuccess)

    JSON.parse(response.body)
  end

  def start
    sh('docker', 'network', 'create', @name)
    sh('docker', 'run', '-d', '--name', "#{@name}-server", '--network', @name,
       '-p', "127.0.0.1:#{@port}:8153", '-v', "#{@work}:/repos", @image)
    log("server #{@image} starting")
    wait_until_healthy
    key = sh('docker', 'exec', "#{@name}-server", 'sh', '-c',
             %q(grep -o 'agentAutoRegisterKey="[^"]*"' /godata/config/cruise-config.xml | cut -d'"' -f2))
    sh('docker', 'run', '-d', '--name', "#{@name}-agent", '--network', @name, '-v', "#{@work}:/repos",
       '-e', "GO_SERVER_URL=http://#{@name}-server:8153/go", '-e', "AGENT_AUTO_REGISTER_KEY=#{key}", AGENT_IMAGE)
    add_config_repo
    log('agent registering, config repository added')
  end

  def wait_until_healthy
    120.times do
      begin
        get('/v1/health')
        return
      rescue StandardError
        sleep(5) # the server is still booting; the loop's end names the failure
      end
    end
    abort("#{@name}: the server never became healthy")
  end

  def add_config_repo
    body = { 'id' => 'repro', 'plugin_id' => 'yaml.config.plugin', 'configuration' => [],
             'material' => { 'type' => 'git', 'attributes' => { 'url' => '/repos/config', 'branch' => 'main', 'auto_update' => true } },
             'rules' => [{ 'directive' => 'allow', 'action' => 'refer', 'type' => 'pipeline_group', 'resource' => 'repro' }] }
    uri = URI("#{@api}/admin/config_repos")
    request = Net::HTTP::Post.new(uri, 'Accept' => 'application/vnd.go.cd.v4+json', 'Content-Type' => 'application/json')
    request.body = JSON.generate(body)
    response = Net::HTTP.start(uri.host, uri.port, read_timeout: 30) { |http| http.request(request) }
    raise "adding the config repository failed: #{response.code} #{response.body}" unless response.is_a?(Net::HTTPSuccess)
  end

  def stop
    if ENV['KEEP'] == '1'
      log("KEEP=1: containers left (#{@name}-server on port #{@port})")
      return
    end
    Open3.capture2e('docker', 'rm', '-f', "#{@name}-agent", "#{@name}-server")
    Open3.capture2e('docker', 'network', 'rm', @name)
  end

  def runs(pipeline)
    get("/pipelines/#{pipeline}/history").fetch('pipelines', [])
  rescue StandardError
    [] # the pipeline is not in the config yet (the YAML plugin has not parsed it)
  end

  def counter(pipeline)
    history = runs(pipeline)
    history.empty? ? 0 : history[0]['counter']
  end

  def describe(run)
    run['build_cause']['material_revisions'].map do |mr|
      mod = mr['modifications'][0]
      description = mr['material']['description']
      mr['material']['type'] == 'Git' ? "#{description.split('URL: ').last[0, 40]}@#{mod['revision'][0, 7]}" : description.split(' ').first
    end
  end

  # ---- steps ----------------------------------------------------------------------------------------------------------
  def built_since(before)
    ALL.select { |p| counter(p) > before[p] }.to_set
  end

  def step(number, spec)
    before = ALL.to_h { |p| [p, counter(p)] }
    commits = spec[:touches].map(&:first).uniq.to_h do |repo|
      [repo, commit(repo, spec[:touches].select { |r, _| r == repo }.map(&:last), spec[:say])]
    end
    expected = spec[@profile.to_sym].to_set
    log("step #{number}: #{spec[:say]}  (commits #{commits.empty? ? 'none' : commits}; expect #{expected.empty? ? 'no run' : expected.sort})")
    built = wait_for(expected, before)
    runs = new_runs(built, before)
    record(number, spec, expected, built, runs, commits)
  end

  def wait_for(expected, before)
    deadline = Time.now + POLL_WAIT
    built = Set.new
    while Time.now < deadline
      built = built_since(before)
      break if !expected.empty? && expected.subset?(built)

      sleep(10)
    end
    return built unless !expected.empty? && expected.subset?(built)

    sleep(SETTLE)
    built_since(before)
  end

  def new_runs(built, before)
    built.sort.to_h do |p|
      fresh = runs(p).select { |r| r['counter'] > before[p] }
      [p, fresh.reverse.map do |r|
        { 'counter' => r['counter'], 'scheduled' => r['scheduled_date'] / 1000.0, 'materials' => describe(r),
          'result' => r['stages'][0]['result'] }
      end]
    end
  end

  def record(number, spec, expected, built, runs, commits)
    times = runs.map { |p, rs| [rs[0]['scheduled'], p] }.sort
    spread = times.size > 1 ? (times.last[0] - times.first[0]).round(1) : 0
    shape = if times.size < 2 then 'single'
            else spread < TOGETHER ? 'together' : 'ordered'
            end
    order = times.map(&:last)
    in_order = shape == 'ordered' && order == ALL.select { |p| built.include?(p) }
    verdict = built == expected && (!spec[:ordered] || in_order) ? 'PASS' : 'DIFF'
    detail = verdict == 'PASS' ? '' : " (missing #{(expected - built).sort}, unexpected #{(built - expected).sort}" \
                                      "#{!spec[:ordered] || in_order ? '' : ", expected A then B then C, got #{shape} #{order}"})"
    log("  #{verdict}: scheduled #{built.empty? ? 'nothing' : built.sort}#{detail}; order #{order}, spread #{spread}s (#{shape})")
    runs.each do |p, rs|
      rs.each do |r|
        log("    #{p} ##{r['counter']} at #{Time.at(r['scheduled']).strftime('%H:%M:%S')} #{r['result']} on #{r['materials'].join(', ')}")
      end
    end
    @results << { 'step' => number, 'say' => spec[:say], 'commits' => commits, 'expected' => expected.sort,
                  'built' => built.sort, 'verdict' => verdict, 'order' => order, 'spread_seconds' => spread, 'shape' => shape,
                  'runs' => runs }
  end

  # Two runs on one working directory corrupt each other (a smoke run and the full matrix both used work/V1-stock, so each
  # saw the other's commits). A live owner makes this run refuse to start.
  def claim_work_dir
    lock = File.join(HERE, 'work', "#{@id}-#{@profile}.lock")
    FileUtils.mkdir_p(File.dirname(lock))
    if File.exist?(lock)
      owner = File.read(lock).strip
      abort("#{@id} #{@profile}: working directory in use by process #{owner}; refusing to start") if owner.match?(/\A\d+\z/) && File.exist?("/proc/#{owner}")
    end
    File.write(lock, Process.pid.to_s)
    lock
  end

  def execute
    lock = claim_work_dir
    log("#{@v[:title]} against #{@image}")
    make_repos
    begin
      start
      @v[:steps].each_with_index { |spec, number| step(number, spec) }
    ensure
      File.write(File.join(@out, 'results.json'), JSON.pretty_generate(
                                                    'variation' => @id, 'profile' => @profile, 'image' => @image,
                                                    'title' => @v[:title], 'results' => @results
                                                  ))
      stop
      FileUtils.rm_f(lock)
    end
    passed = @results.count { |r| r['verdict'] == 'PASS' }
    log("#{passed}/#{@results.size} steps matched their expectation; evidence in #{@out}")
  end
end

# ---- documentation, generated from the same data the runs use -----------------------------------------------------------
def short(pipeline)
  pipeline.split('-').first.upcase
end

def topology(variation)
  lines = ['flowchart LR']
  variation[:repos].each { |repo, files| lines << "  #{repo}[(\"repo #{repo}<br/>#{files.join(', ')}\")]" }
  variation[:pipelines].each do |name, materials|
    lines << "  #{short(name)}[#{name}]"
    materials.each do |m|
      if m[:kind] == 'git'
        label = m[:includes] ? m[:includes].join(', ') : 'whole repo'
        lines << "  #{m[:repo]} -- \"#{label}\" --> #{short(name)}"
      else
        style = m[:ignore] ? 'ignore_for_scheduling' : 'ordinary dependency'
        lines << "  #{short(m[:upstream])} -. \"#{style}\" .-> #{short(name)}"
      end
    end
  end
  lines.join("\n")
end

def latest_results
  Dir.glob(File.join(HERE, 'out', 'variations', '*', 'results.json')).sort.to_h do |path|
    data = JSON.parse(File.read(path))
    [[data['variation'], data['profile']], data]
  end
end

def names(pipelines)
  pipelines.empty? ? 'none' : pipelines.map { |p| short(p) }.join(', ')
end

# One line per variation for the overview: what the layout is and what it is there to show.
SHOWS = {
  'V1' => 'Control: three separate repositories. No shared material, so no difference between the servers.',
  'V2' => "B and C also monitor a file in A's repository. Reproduces the freeze and the lost order.",
  'V3' => 'A and B share a repository, C is separate and monitors a file in it. The freeze and the lost order again.',
  'V4' => 'Control: one repository, ordinary dependencies. The behaviour the refined patch has to keep.',
  'V5' => "V2 without dependency materials. Isolation run: does the upstream's run drag B and C along?",
  'V6' => "V2 with only B depending on A. Isolation run: a downstream sharing the repository follows its upstream's run.",
  'V7' => 'One repository with three whitelists and ignored dependencies: the shape that was originally reported.'
}.freeze

def anchor(key)
  "#{key}: #{VARIATIONS[key][:title]}".downcase.gsub(/[^a-z0-9 _-]/, '').tr(' ', '-') # GitHub's heading anchor
end

def report_header
  ['# Fan-in variations: separate and partly shared repositories', '',
   'Generated by `variations.rb doc` from the data the runs use; edit the data, not this file.', '',
   "Stock server: `#{STOCK_IMAGE}`. Patched server: `#{PATCHED_IMAGE}`. Material poll is about a minute, so each step waits " \
   "up to #{POLL_WAIT}s for an expected run and #{SETTLE}s more for an unexpected one. A step PASSes when the set of " \
   'pipelines that scheduled equals the expectation written before the run; DIFF is a finding. ' \
   '❌ marks behaviour that is broken: the server froze a pipeline that should have built, or lost the order the dependency ' \
   'gives. PASS means the run matched the prediction, including when the predicted behaviour is a broken one.', '',
   '## Overview', '', '| Variation | Layout | What it shows |', '|---|---|---|']
end

def observed_cell(key, profile, number, spec, results)
  data = results[[key, profile]]
  row = data && data['results'].find { |r| r['step'] == number }
  return 'not run' if row.nil?

  shape = row['shape'] == 'single' ? '' : " (#{row['shape']}, #{row['spread_seconds']}s)"
  # Judged now, against the expectation in this file, not as recorded when the run happened.
  in_order = row['shape'] == 'ordered' && row['order'] == ALL.select { |p| row['built'].include?(p) }
  verdict = row['built'].sort == spec[profile.to_sym].sort && (!spec[:ordered] || profile != 'refined' || in_order) ? 'PASS' : 'DIFF'
  text = "#{verdict}: #{names(row['built'])}#{shape}"
  # Broken: what the server did differs from the intent (the refined expectation): a freeze, or the order lost.
  broken = profile != 'refined' && (row['built'].sort != spec[:refined].sort || (spec[:ordered] && !in_order))
  broken ? "❌ **#{text}**" : text
end

def render_variation(key, results)
  variation = VARIATIONS[key]
  out = ["## #{key}: #{variation[:title]}", '', '```mermaid', topology(variation), '```', '',
         '| Step | What changes | Expected stock | Expected patched | Expected refined | Observed stock | Observed patched | Observed refined |',
         '|---|---|---|---|---|---|---|---|']
  variation[:steps].each_with_index do |spec, number|
    cells = %w[stock patched refined].map { |profile| observed_cell(key, profile, number, spec, results) }
    expected = %i[stock patched].map do |profile|
      spec[profile].sort == spec[:refined].sort ? names(spec[profile]) : "❌ **#{names(spec[profile])}**"
    end
    wants = "#{names(spec[:refined])}#{spec[:ordered] ? ' in order' : ''}"
    out << "| #{number} | #{spec[:say]} | #{expected[0]} | #{expected[1]} | #{wants} | #{cells.join(' | ')} |"
  end
  out << ''
end

def render_doc
  results = latest_results
  keys = VARIATIONS.keys.sort_by { |k| Integer(k[1..]) }
  out = report_header
  keys.each { |key| out << "| [#{key}](##{anchor(key)}) | #{VARIATIONS[key][:title]} | #{SHOWS.fetch(key, '')} |" }
  out << ''
  keys.each { |key| out.concat(render_variation(key, results)) }
  out.join("\n")
end

def run_all(profiles)
  port = 18_200
  pids = VARIATIONS.keys.flat_map do |variation|
    profiles.map do |profile|
      pid = spawn(RbConfig.ruby, __FILE__, 'run', variation, profile, port.to_s)
      port += 1
      pid
    end
  end
  codes = pids.map { |pid| Process.wait2(pid).last.exitstatus }
  puts "all runs finished, exit codes: #{codes}"
end

if $PROGRAM_NAME == __FILE__
  command = ARGV[0].to_s
  profiles = %w[stock patched refined]
  if command == 'all'
    run_all(ARGV.size > 1 ? ARGV[1..] : profiles) # e.g. ./variations.rb all refined
  elsif command == 'doc'
    puts render_doc
  elsif command == 'run' && ARGV.size >= 3 && VARIATIONS.key?(ARGV[1]) && profiles.include?(ARGV[2])
    Run.new(ARGV[1], ARGV[2], Integer(ARGV[3] || 18_199)).execute
  else
    abort(File.read(__FILE__)[/^# The fan-in variations.*?^# Images:[^\n]*/m].gsub(/^# ?/, ''))
  end
end
