/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.distributed.DistBuildStatus;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.LogRecord;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.NetworkEvent;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.rules.TestStatusMessageEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import javax.annotation.Nullable;

/**
 * Console that provides rich, updating ansi output about the current build.
 */
public class SuperConsoleEventBusListener extends AbstractConsoleEventBusListener {
  /**
   * Maximum expected rendered line length so we can start with a decent
   * size of line rendering buffer.
   */
  private static final int EXPECTED_MAXIMUM_RENDERED_LINE_LENGTH = 128;

  private static final Logger LOG = Logger.get(SuperConsoleEventBusListener.class);

  private final Locale locale;
  private final Function<Long, String> formatTimeFunction;
  private final Optional<WebServer> webServer;
  private final ConcurrentMap<Long, Optional<? extends TestSummaryEvent>>
      threadsToRunningTestSummaryEvent;
  private final ConcurrentMap<Long, Optional<? extends TestStatusMessageEvent>>
      threadsToRunningTestStatusMessageEvent;
  private final ConcurrentMap<Long, Optional<? extends LeafEvent>> threadsToRunningStep;

  // Counts the rules that have updated rule keys.
  private final AtomicInteger updated = new AtomicInteger(0);

  // Counts the number of cache misses and errors, respectively.
  private final AtomicInteger cacheMisses = new AtomicInteger(0);
  private final AtomicInteger cacheErrors = new AtomicInteger(0);

  private final ConcurrentLinkedQueue<ConsoleEvent> logEvents;

  private final ScheduledExecutorService renderScheduler;

  private final TestResultFormatter testFormatter;

  private final AtomicInteger testPasses = new AtomicInteger(0);
  private final AtomicInteger testFailures = new AtomicInteger(0);
  private final AtomicInteger testSkips = new AtomicInteger(0);

  private final AtomicReference<TestRunEvent.Started> testRunStarted;
  private final AtomicReference<TestRunEvent.Finished> testRunFinished;

  private final ImmutableList.Builder<String> testReportBuilder = ImmutableList.builder();
  private final ImmutableList.Builder<TestStatusMessage> testStatusMessageBuilder =
      ImmutableList.builder();

  private final AtomicBoolean anyWarningsPrinted = new AtomicBoolean(false);
  private final AtomicBoolean anyErrorsPrinted = new AtomicBoolean(false);

  private final int defaultThreadLineLimit;
  private final int threadLineLimitOnWarning;
  private final int threadLineLimitOnError;
  private final boolean shouldAlwaysSortThreadsByTime;

  private Optional<DistBuildStatus> distBuildStatus;
  private final DateFormat dateFormat;
  private int lastNumLinesPrinted;

  public SuperConsoleEventBusListener(
      SuperConsoleConfig config,
      Console console,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Optional<WebServer> webServer,
      Locale locale,
      Path testLogPath,
      TimeZone timeZone) {
    super(console, clock, locale, executionEnvironment);
    this.locale = locale;
    this.formatTimeFunction = new Function<Long, String>(){
        @Override
        public String apply(Long elapsedTimeMs) {
          return formatElapsedTime(elapsedTimeMs);
        }
    };
    this.webServer = webServer;
    this.threadsToRunningTestSummaryEvent = new ConcurrentHashMap<>(
        executionEnvironment.getAvailableCores());
    this.threadsToRunningTestStatusMessageEvent = new ConcurrentHashMap<>(
        executionEnvironment.getAvailableCores());
    this.threadsToRunningStep = new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());

    this.logEvents = new ConcurrentLinkedQueue<>();

    this.renderScheduler = Executors.newScheduledThreadPool(1,
        new ThreadFactoryBuilder().setNameFormat(getClass().getSimpleName() + "-%d").build());
    this.testFormatter = new TestResultFormatter(
        console.getAnsi(),
        console.getVerbosity(),
        summaryVerbosity,
        locale,
        Optional.of(testLogPath));
    this.testRunStarted = new AtomicReference<>();
    this.testRunFinished = new AtomicReference<>();

    this.defaultThreadLineLimit = config.getThreadLineLimit();
    this.threadLineLimitOnWarning = config.getThreadLineLimitOnWarning();
    this.threadLineLimitOnError = config.getThreadLineLimitOnError();
    this.shouldAlwaysSortThreadsByTime = config.shouldAlwaysSortThreadsByTime();
    this.distBuildStatus = Optional.absent();

    this.dateFormat = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]", this.locale);
    this.dateFormat.setTimeZone(timeZone);
  }

  /**
   * Schedules a runnable that updates the console output at a fixed interval.
   */
  public void startRenderScheduler(long renderInterval, TimeUnit timeUnit) {
    LOG.debug("Starting render scheduler (interval %d ms)", timeUnit.toMillis(renderInterval));
    renderScheduler.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        try {
          SuperConsoleEventBusListener.this.render();
        } catch (Error | RuntimeException e) {
          LOG.error(e, "Rendering exception");
          throw e;
        }
      }
    }, /* initialDelay */ renderInterval, /* period */ renderInterval, timeUnit);
  }

  /**
   * Shuts down the thread pool and cancels the fixed interval runnable.
   */
  private synchronized void stopRenderScheduler() {
    LOG.debug("Stopping render scheduler");
    renderScheduler.shutdownNow();
  }

  @VisibleForTesting
  synchronized void render() {
    LOG.verbose("Rendering");
    String lastRenderClear = clearLastRender();
    ImmutableList<String> lines = createRenderLinesAtTime(clock.currentTimeMillis());
    ImmutableList<String> logLines = createLogRenderLines();
    lastNumLinesPrinted = lines.size();

    // Synchronize on the DirtyPrintStreamDecorator to prevent interlacing of output.
    // We don't log immediately so we avoid locking the console handler to avoid deadlocks.
    boolean stdoutDirty;
    boolean stderrDirty;
    synchronized (console.getStdOut()) {
      synchronized (console.getStdErr()) {
        // If another source has written to stderr or stdout, stop rendering with the SuperConsole.
        // We need to do this to keep our updates consistent.
        stdoutDirty = console.getStdOut().isDirty();
        stderrDirty = console.getStdErr().isDirty();
        if (stdoutDirty || stderrDirty) {
          stopRenderScheduler();
        } else if (!lastRenderClear.isEmpty() || !lines.isEmpty() || !logLines.isEmpty()) {
          Iterable<String> renderedLines = Iterables.concat(
              MoreIterables.zipAndConcat(
                  logLines,
                  Iterables.cycle("\n")),
              ansi.asNoWrap(
                  MoreIterables.zipAndConcat(
                      lines,
                      Iterables.cycle("\n"))));
          StringBuilder fullFrame = new StringBuilder(lastRenderClear);
          for (String part : renderedLines) {
            fullFrame.append(part);
          }
          console.getStdErr().getRawStream().print(fullFrame);
        }
      }
    }
    if (stdoutDirty || stderrDirty) {
      LOG.debug(
          "Stopping console output (stdout dirty %s, stderr dirty %s).",
          stdoutDirty, stderrDirty);
    }
  }

  /**
   * Creates a list of lines to be rendered at a given time.
   * @param currentTimeMillis The time in ms to use when computing elapsed times.
   */
  @VisibleForTesting
  ImmutableList<String> createRenderLinesAtTime(long currentTimeMillis) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();

    // print latest distributed build debug info lines
    if (buildStarted != null && buildStarted.isDistributedBuild()) {
      getDistBuildDebugInfo(lines);
    }

    // If we have not yet started processing the BUCK files, show parse times
    if (parseStarted.isEmpty() && parseFinished.isEmpty()) {
      logEventPair(
          "PARSING BUCK FILES",
          /* suffix */ Optional.<String>absent(),
          currentTimeMillis,
          /* offsetMs */ 0L,
          projectBuildFileParseStarted,
          projectBuildFileParseFinished,
          getEstimatedProgressOfProcessingBuckFiles(),
          lines);
    }

    long parseTime = logEventPair("PROCESSING BUCK FILES",
        /* suffix */ Optional.<String>absent(),
        currentTimeMillis,
        /* offsetMs */ 0L,
        buckFilesProcessing.values(),
        getEstimatedProgressOfProcessingBuckFiles(),
        lines);

    logEventPair(
        "GENERATING PROJECT",
        Optional.<String>absent(),
        currentTimeMillis,
        /* offsetMs */ 0L,
        projectGenerationStarted,
        projectGenerationFinished,
        getEstimatedProgressOfGeneratingProjectFiles(),
        lines);

    // If parsing has not finished, then there is no build rule information to print yet.
    if (parseTime != UNFINISHED_EVENT_PAIR) {
      lines.add(getNetworkStatsLine(buildFinished));
      if (buildStarted != null && buildStarted.isDistributedBuild()) {
        lines.add(getDistBuildStatusLine());
      }
      // Log build time, excluding time spent in parsing.
      String jobSummary = null;
      if (ruleCount.isPresent()) {
        List<String> columns = Lists.newArrayList();
        columns.add(String.format(locale, "%d/%d JOBS", numRulesCompleted.get(), ruleCount.get()));
        columns.add(String.format(locale, "%d UPDATED", updated.get()));
        if (ruleCount.isPresent() && ruleCount.get() > 0) {
          // Measure CACHE HIT % based on total cache misses and the theoretical total number of
          // build rules.
          // REASON: If we only look at cache_misses and processed rules we are strongly biasing
          // the result toward misses. Basically misses weight more than hits.
          // One MISS will traverse all its dependency subtree potentially generating misses for
          // all internal Nodes; worst case generating N cache_misses.
          // One HIT will prevent any further traversal of dependency sub-tree nodes so it will
          // only count as one cache_hit even though it saved us from fetching N nodes.
          columns.add(
              String.format(
                  locale,
                  "%d [%.1f%%] CACHE MISS",
                  cacheMisses.get(),
                  100 * (double) cacheMisses.get() / ruleCount.get()));
          if (cacheErrors.get() > 0) {
            columns.add(
                String.format(
                    locale,
                    "%d [%.1f%%] CACHE ERRORS",
                    cacheErrors.get(),
                    100 * (double) cacheErrors.get() / updated.get()));
          }
        }
        jobSummary = "(" + Joiner.on(", ").join(columns) + ")";
      }

      // If the Daemon is running and serving web traffic, print the URL to the Chrome Trace.
      String buildTrace = null;
      if (buildFinished != null && webServer.isPresent()) {
        Optional<Integer> port = webServer.get().getPort();
        if (port.isPresent()) {
          buildTrace = String.format(
              locale,
              "Details: http://localhost:%s/trace/%s",
              port.get(),
              buildFinished.getBuildId());
        }
      }


      if (buildStarted == null) {
        // All steps past this point require a build.
        return lines.build();
      }
      String suffix = Joiner.on(" ")
          .join(FluentIterable.of(new String[] {jobSummary, buildTrace})
              .filter(Predicates.notNull()));
      Optional<String> suffixOptional =
          suffix.isEmpty() ? Optional.<String>absent() : Optional.of(suffix);
      // Check to see if the build encompasses the time spent parsing. This is true for runs of
      // buck build but not so for runs of e.g. buck project. If so, subtract parse times
      // from the build time.
      long buildStartedTime = buildStarted.getTimestamp();
      long buildFinishedTime = buildFinished != null
          ? buildFinished.getTimestamp()
          : currentTimeMillis;
      Collection<EventPair> processingEvents = getEventsBetween(buildStartedTime,
          buildFinishedTime,
          buckFilesProcessing.values());
      long offsetMs = getTotalCompletedTimeFromEventPairs(processingEvents);

      long buildTime = logEventPair(
          "BUILDING",
          suffixOptional,
          currentTimeMillis,
          offsetMs, // parseTime,
          this.buildStarted,
          this.buildFinished,
          getApproximateBuildProgress(),
          lines);

      int maxThreadLines = defaultThreadLineLimit;
      if (anyWarningsPrinted.get() && threadLineLimitOnWarning < maxThreadLines) {
        maxThreadLines = threadLineLimitOnWarning;
      }
      if (anyErrorsPrinted.get() && threadLineLimitOnError < maxThreadLines) {
        maxThreadLines = threadLineLimitOnError;
      }
      if (buildTime == UNFINISHED_EVENT_PAIR) {
        ThreadStateRenderer renderer = new BuildThreadStateRenderer(
            ansi,
            formatTimeFunction,
            currentTimeMillis,
            threadsToRunningStep,
            accumulatedTimeTracker);
        renderLines(renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
      }

      long testRunTime = logEventPair(
          "TESTING",
          renderTestSuffix(),
          currentTimeMillis,
          0, /* offsetMs */
          testRunStarted.get(),
          testRunFinished.get(),
          Optional.<Double>absent(),
          lines);

      if (testRunTime == UNFINISHED_EVENT_PAIR) {
        ThreadStateRenderer renderer = new TestThreadStateRenderer(
            ansi,
            formatTimeFunction,
            currentTimeMillis,
            threadsToRunningTestSummaryEvent,
            threadsToRunningTestStatusMessageEvent,
            threadsToRunningStep,
            accumulatedTimeTracker);
        renderLines(renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
      }

      logEventPair("INSTALLING",
          /* suffix */ Optional.<String>absent(),
          currentTimeMillis,
          0L,
          installStarted,
          installFinished,
          Optional.<Double>absent(),
          lines);

      logEventPair("HTTP CACHE UPLOAD",
          renderHttpUploads(),
          currentTimeMillis,
          0,
          firstHttpCacheUploadScheduled.get(),
          httpShutdownEvent,
          Optional.<Double>absent(),
          lines);
    }
    return lines.build();
  }

  private String getNetworkStatsLine(
      @Nullable BuildEvent.Finished finishedEvent) {
    String parseLine = (finishedEvent != null ? "[-] " : "[+] ") + "DOWNLOADING" + "...";
    List<String> columns = Lists.newArrayList();
    if (finishedEvent != null) {
      Pair<Double, SizeUnit> avgDownloadSpeed =
          networkStatsKeeper.getAverageDownloadSpeed();
      Pair<Double, SizeUnit> readableSpeed =
          SizeUnit.getHumanReadableSize(
              avgDownloadSpeed.getFirst(),
              avgDownloadSpeed.getSecond());
      columns.add(
          String.format(
              locale,
              "%.2f %s/S " + "AVG",
              readableSpeed.getFirst(),
              readableSpeed.getSecond().getAbbreviation()));
    } else {
      Pair<Double, SizeUnit> downloadSpeed = networkStatsKeeper.getDownloadSpeed();
      Pair<Double, SizeUnit> readableDownloadSpeed =
          SizeUnit.getHumanReadableSize(downloadSpeed.getFirst(), downloadSpeed.getSecond());
      columns.add(
          String.format(
              locale,
              "%.2f %s/S",
              readableDownloadSpeed.getFirst(),
              readableDownloadSpeed.getSecond().getAbbreviation()
          )
      );
    }
    Pair<Long, SizeUnit> bytesDownloaded = networkStatsKeeper.getBytesDownloaded();
    Pair<Double, SizeUnit> readableBytesDownloaded =
        SizeUnit.getHumanReadableSize(
            bytesDownloaded.getFirst(),
            bytesDownloaded.getSecond());
    columns.add(
        String.format(
            locale,
            "TOTAL: %.2f %s",
            readableBytesDownloaded.getFirst(),
            readableBytesDownloaded.getSecond().getAbbreviation()));
    columns.add(
        String.format(
            locale,
            "%d Artifacts",
            networkStatsKeeper.getDownloadedArtifactDownloaded()));
    return parseLine + " " + "(" + Joiner.on(", ").join(columns) + ")";
  }

  private String getDistBuildStatusLine()  {
    // create a local reference to avoid inconsistencies
    Optional<DistBuildStatus> distBuildStatus = this.distBuildStatus;
    boolean finished = distBuildStatus.isPresent() &&
        (distBuildStatus.get().getStatus() == BuildStatus.FINISHED_SUCCESSFULLY ||
            distBuildStatus.get().getStatus() == BuildStatus.FAILED);
    String parseLine = finished ? "[-] " : "[+] ";

    parseLine += "DISTBUILD STATUS: ";

    if (!distBuildStatus.isPresent()) {
      parseLine += "INIT...";
      return parseLine;
    }
    parseLine += distBuildStatus.get().getStatus().toString() + "...";

    if (!finished) {
      parseLine += " ETA: " + formatElapsedTime(distBuildStatus.get().getETAMillis());
    }
    if (distBuildStatus.get().getMessage().isPresent()) {
      parseLine += " (" + distBuildStatus.get().getMessage().get() + ")";
    }
    return parseLine;
  }

  private void getDistBuildDebugInfo(ImmutableList.Builder<String> lines) {
    // create a local reference to avoid inconsistencies
    Optional<DistBuildStatus> distBuildStatus = this.distBuildStatus;
    if (distBuildStatus.isPresent() && distBuildStatus.get().getLogBook().isPresent())  {
      lines.add(ansi.asWarningText("Distributed build debug info:"));
      for (LogRecord log : distBuildStatus.get().getLogBook().get()) {
        String dateString = dateFormat.format(new Date(log.getTimestampMillis()));
        lines.add(ansi.asWarningText(dateString + " " + log.getName()));
      }
      anyWarningsPrinted.set(true);
    }
  }

  /**
   * Adds log messages for rendering.
   */
  @VisibleForTesting
  ImmutableList<String> createLogRenderLines() {
    ImmutableList.Builder<String> logEventLinesBuilder = ImmutableList.builder();
    ConsoleEvent logEvent;
    while ((logEvent = logEvents.poll()) != null) {
      formatConsoleEvent(logEvent, logEventLinesBuilder);
      if (logEvent.getLevel().equals(Level.WARNING)) {
        anyWarningsPrinted.set(true);
      } else if (logEvent.getLevel().equals(Level.SEVERE)) {
        anyErrorsPrinted.set(true);
      }
    }
    return logEventLinesBuilder.build();
  }

  public void renderLines(
      ThreadStateRenderer renderer,
      ImmutableList.Builder<String> lines,
      int maxLines,
      boolean alwaysSortByTime) {
    int threadCount = renderer.getThreadCount();
    int fullLines = threadCount;
    boolean useCompressedLine = false;
    if (threadCount > maxLines) {
      // One line will be used for the remaining threads that don't get their own line.
      fullLines = maxLines - 1;
      useCompressedLine = true;
    }
    int threadsWithShortStatus = threadCount - fullLines;
    boolean sortByTime = alwaysSortByTime || useCompressedLine;
    ImmutableList<Long> threadIds = renderer.getSortedThreadIds(sortByTime);
    StringBuilder lineBuilder = new StringBuilder(EXPECTED_MAXIMUM_RENDERED_LINE_LENGTH);
    for (int i = 0; i < fullLines; ++i) {
      long threadId = threadIds.get(i);
      lines.add(renderer.renderStatusLine(threadId, lineBuilder));
    }
    if (useCompressedLine) {
      lineBuilder.delete(0, lineBuilder.length());
      lineBuilder.append(" |=> ");
      lineBuilder.append(threadsWithShortStatus);
      if (fullLines == 0) {
        lineBuilder.append(" THREADS:");
      } else {
        lineBuilder.append(" MORE THREADS:");
      }
      for (int i = fullLines; i < threadIds.size(); ++i) {
        long threadId = threadIds.get(i);
        lineBuilder.append(" ");
        lineBuilder.append(renderer.renderShortStatus(threadId));
      }
      lines.add(lineBuilder.toString());
    }
  }

  private Optional<String> renderTestSuffix() {
    int testPassesVal = testPasses.get();
    int testFailuresVal = testFailures.get();
    int testSkipsVal = testSkips.get();
    if (testSkipsVal > 0) {
      return Optional.of(
          String.format(
              locale,
              "(%d PASS/%d SKIP/%d FAIL)",
              testPassesVal,
              testSkipsVal,
              testFailuresVal));
    } else if (testPassesVal > 0 || testFailuresVal > 0) {
      return Optional.of(
          String.format(
              locale,
              "(%d PASS/%d FAIL)",
              testPassesVal,
              testFailuresVal));
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return A string of ansi characters that will clear the last set of lines printed by
   *     {@link SuperConsoleEventBusListener#createRenderLinesAtTime(long)}.
   */
  private String clearLastRender() {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < lastNumLinesPrinted; ++i) {
      result.append(ansi.cursorPreviousLine(1));
      result.append(ansi.clearLine());
    }
    return result.toString();
  }

  @Override
  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    super.buildRuleStarted(started);
  }

  @Override
  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    super.buildRuleFinished(finished);
    if (finished.getStatus() == BuildRuleStatus.SUCCESS) {
      CacheResult cacheResult = finished.getCacheResult();
      switch (cacheResult.getType()) {
        case MISS:
          cacheMisses.incrementAndGet();
          break;
        case ERROR:
          cacheErrors.incrementAndGet();
          break;
        case HIT:
        case IGNORED:
        case LOCAL_KEY_UNCHANGED_HIT:
          break;
      }
      if (cacheResult.getType() != CacheResultType.LOCAL_KEY_UNCHANGED_HIT) {
        updated.incrementAndGet();
      }
    }
  }

  @Override
  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    super.buildRuleSuspended(suspended);
  }

  @Override
  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    super.buildRuleResumed(resumed);
  }

  @Subscribe
  public void stepStarted(StepEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.<StepEvent>absent());
  }

  @Subscribe
  public void artifactCacheStarted(ArtifactCacheEvent.Started started) {
    if (started.getInvocationType() == ArtifactCacheEvent.InvocationType.SYNCHRONOUS) {
      threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
    }
  }

  @Subscribe
  public void artifactCacheFinished(ArtifactCacheEvent.Finished finished) {
    if (finished.getInvocationType() == ArtifactCacheEvent.InvocationType.SYNCHRONOUS) {
      threadsToRunningStep.put(finished.getThreadId(), Optional.<StepEvent>absent());
    }
  }

  @Subscribe
  public void artifactCompressionStarted(ArtifactCompressionEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void artifactCompressionFinished(ArtifactCompressionEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.<StepEvent>absent());
  }

  @Override
  @Subscribe
  public void distributedBuildStatus(DistBuildStatusEvent event) {
    super.distributedBuildStatus(event);
    distBuildStatus = Optional.of(event.getStatus());
  }

  @Subscribe
  public void testRunStarted(TestRunEvent.Started event) {
    boolean set = testRunStarted.compareAndSet(null, event);
    Preconditions.checkState(set, "Test run should not start while test run in progress");
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    testFormatter.runStarted(builder,
        event.isRunAllTests(),
        event.getTestSelectorList(),
        event.shouldExplainTestSelectorList(),
        event.getTargetNames(),
        TestResultFormatter.FormatMode.AFTER_TEST_RUN);
    synchronized (testReportBuilder) {
      testReportBuilder.addAll(builder.build());
    }
  }

  @Subscribe
  public void testRunFinished(TestRunEvent.Finished finished) {
    boolean set = testRunFinished.compareAndSet(null, finished);
    Preconditions.checkState(set, "Test run should not finish after test run already finished");

    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (TestResults results : finished.getResults()) {
      testFormatter.reportResult(builder, results);
    }
    ImmutableList<TestStatusMessage> testStatusMessages;
    synchronized (testStatusMessageBuilder) {
      testStatusMessages = testStatusMessageBuilder.build();
    }
    testFormatter.runComplete(builder, finished.getResults(), testStatusMessages);
    String testOutput;
    synchronized (testReportBuilder) {
      testReportBuilder.addAll(builder.build());
      testOutput = Joiner.on('\n').join(testReportBuilder.build());
    }
    // We're about to write to stdout, so make sure we render the final frame before we do.
    render();
    synchronized (console.getStdOut()) {
      console.getStdOut().println(testOutput);
    }
  }

  @Subscribe
  public void testStatusMessageStarted(TestStatusMessageEvent.Started started) {
    threadsToRunningTestStatusMessageEvent.put(started.getThreadId(), Optional.of(started));
    synchronized (testStatusMessageBuilder) {
      testStatusMessageBuilder.add(started.getTestStatusMessage());
    }
  }

  @Subscribe
  public void testStatusMessageFinished(TestStatusMessageEvent.Finished finished) {
    threadsToRunningTestStatusMessageEvent.put(
        finished.getThreadId(),
        Optional.<TestStatusMessageEvent>absent());
    synchronized (testStatusMessageBuilder) {
      testStatusMessageBuilder.add(finished.getTestStatusMessage());
    }
  }

  @Subscribe
  public void testSummaryStarted(TestSummaryEvent.Started started) {
    threadsToRunningTestSummaryEvent.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void testSummaryFinished(TestSummaryEvent.Finished finished) {
    threadsToRunningTestSummaryEvent.put(
        finished.getThreadId(),
        Optional.<TestSummaryEvent>absent());
    TestResultSummary testResult = finished.getTestResultSummary();
    ResultType resultType = testResult.getType();
    switch (resultType) {
      case SUCCESS:
        testPasses.incrementAndGet();
        break;
      case FAILURE:
        testFailures.incrementAndGet();
        // We don't use TestResultFormatter.reportResultSummary() here since that also
        // includes the stack trace and stdout/stderr.
        logEvents.add(
            ConsoleEvent.severe(
                String.format(
                    locale,
                    "%s %s %s: %s",
                    testResult.getType().toString(),
                    testResult.getTestCaseName(),
                    testResult.getTestName(),
                    testResult.getMessage())));
        break;
      case ASSUMPTION_VIOLATION:
        testSkips.incrementAndGet();
        break;
      case DRY_RUN:
        break;
    }
  }

  @Subscribe
  public void logEvent(ConsoleEvent event) {
    logEvents.add(event);
  }

  @Subscribe
  public void bytesReceived(NetworkEvent.BytesReceivedEvent bytesReceivedEvent) {
    networkStatsKeeper.bytesReceived(bytesReceivedEvent);
  }

  @Override
  public synchronized void close() throws IOException {
    stopRenderScheduler();
    networkStatsKeeper.stopScheduler();
    render(); // Ensure final frame is rendered.
  }
}
