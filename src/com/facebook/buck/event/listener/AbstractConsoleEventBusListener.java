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
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.i18n.NumberFormatter;
import com.facebook.buck.json.ParseBuckFileEvent;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.ActionGraphEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import com.google.common.eventbus.Subscribe;

import java.io.Closeable;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import javax.annotation.Nullable;

/**
 * Base class for {@link BuckEventListener}s responsible for outputting information about the
 * running build to {@code stderr}.
 */
public abstract class AbstractConsoleEventBusListener implements BuckEventListener, Closeable {
  private static final NumberFormatter TIME_FORMATTER = new NumberFormatter(
      new Function<Locale, NumberFormat>() {
        @Override
        public NumberFormat apply(Locale locale) {
          // Yes, this is the only way to apply and localize a pattern to a NumberFormat.
          NumberFormat numberFormat = NumberFormat.getIntegerInstance(locale);
          Preconditions.checkState(numberFormat instanceof DecimalFormat);
          DecimalFormat decimalFormat = (DecimalFormat) numberFormat;
          decimalFormat.applyPattern("0.0s");
          return decimalFormat;
        }
      });

  protected static final long UNFINISHED_EVENT_PAIR = -1;
  protected final Console console;
  protected final Clock clock;
  protected final Ansi ansi;
  private final Locale locale;

  @Nullable
  protected volatile ProjectBuildFileParseEvents.Started projectBuildFileParseStarted;
  @Nullable
  protected volatile ProjectBuildFileParseEvents.Finished projectBuildFileParseFinished;

  @Nullable
  protected volatile ProjectGenerationEvent.Started projectGenerationStarted;
  @Nullable
  protected volatile ProjectGenerationEvent.Finished projectGenerationFinished;

  protected ConcurrentLinkedDeque<ParseEvent.Started> parseStarted;
  protected ConcurrentLinkedDeque<ParseEvent.Finished> parseFinished;

  protected ConcurrentLinkedDeque<ActionGraphEvent.Started> actionGraphStarted;
  protected ConcurrentLinkedDeque<ActionGraphEvent.Finished> actionGraphFinished;

  protected ConcurrentHashMap<EventKey, EventPair> buckFilesProcessing;

  @Nullable
  protected volatile BuildEvent.Started buildStarted;
  @Nullable
  protected volatile BuildEvent.Finished buildFinished;

  @Nullable
  protected volatile InstallEvent.Started installStarted;
  @Nullable
  protected volatile InstallEvent.Finished installFinished;

  protected AtomicReference<HttpArtifactCacheEvent.Scheduled> firstHttpCacheUploadScheduled =
      new AtomicReference<>();

  protected final AtomicInteger httpArtifactUploadsScheduledCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadsStartedCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadedCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadFailedCount = new AtomicInteger(0);

  @Nullable
  protected volatile HttpArtifactCacheEvent.Shutdown httpShutdownEvent;

  protected volatile Optional<Integer> ruleCount = Optional.absent();

  protected final AtomicInteger numRulesCompleted = new AtomicInteger();

  protected Optional<ProgressEstimator> progressEstimator = Optional.<ProgressEstimator>absent();

  protected final NetworkStatsKeeper networkStatsKeeper;

  private volatile double distributedBuildProgress = 0;

  protected AccumulatedTimeTracker accumulatedTimeTracker;

  public AbstractConsoleEventBusListener(
      Console console,
      Clock clock,
      Locale locale,
      ExecutionEnvironment executionEnvironment
  ) {
    this.console = console;
    this.clock = clock;
    this.locale = locale;
    this.ansi = console.getAnsi();

    this.projectBuildFileParseStarted = null;
    this.projectBuildFileParseFinished = null;

    this.projectGenerationStarted = null;
    this.projectGenerationFinished = null;

    this.parseStarted = new ConcurrentLinkedDeque<>();
    this.parseFinished = new ConcurrentLinkedDeque<>();

    this.actionGraphStarted = new ConcurrentLinkedDeque<>();
    this.actionGraphFinished = new ConcurrentLinkedDeque<>();

    this.buckFilesProcessing = new ConcurrentHashMap<>();

    this.buildStarted = null;
    this.buildFinished = null;

    this.installStarted = null;
    this.installFinished = null;

    this.networkStatsKeeper = new NetworkStatsKeeper();

    this.accumulatedTimeTracker = new AccumulatedTimeTracker(executionEnvironment);
  }

  public void setProgressEstimator(ProgressEstimator estimator) {
    progressEstimator = Optional.<ProgressEstimator>of(estimator);
  }

  protected String formatElapsedTime(long elapsedTimeMs) {
    return TIME_FORMATTER.format(locale, elapsedTimeMs / 1000.0);
  }

  protected Optional<Double> getApproximateBuildProgress() {
    if (buildStarted != null && buildStarted.isDistributedBuild()) {
      return Optional.of(distributedBuildProgress);
    } else {
      if (progressEstimator.isPresent()) {
        return progressEstimator.get().getApproximateBuildProgress();
      } else {
        return Optional.<Double>absent();
      }
    }
  }

  protected Optional<Double> getEstimatedProgressOfGeneratingProjectFiles() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getEstimatedProgressOfGeneratingProjectFiles();
    } else {
      return Optional.<Double>absent();
    }
  }

  protected Optional<Double> getEstimatedProgressOfProcessingBuckFiles() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getEstimatedProgressOfProcessingBuckFiles();
    } else {
      return Optional.<Double>absent();
    }
  }

  /**
   * Filter a list of events and return the subset that fall between the given start and end
   * timestamps. Preserves ordering if the given iterable was ordered. Will replace event pairs
   * that straddle the boundary with {@link com.facebook.buck.event.listener.ProxyBuckEvent}
   * instances, so that the resulting collection is strictly contained within the boundaries.
   * @param start the start timestamp (inclusive)
   * @param end the end timestamp (also inclusive)
   * @param eventPairs the events to filter.
   * @return a list of all events from the given iterable that fall between the given start and end
   *         times. If an event straddles the given start or end, it will be replaced with a proxy
   *         event pair that cuts off at exactly the start or end.
   */
  protected static Collection<EventPair> getEventsBetween(long start,
      long end,
      Iterable<EventPair> eventPairs) {
    List<EventPair> outEvents = new ArrayList<>();
    for (EventPair ep : eventPairs) {
      long startTime = ep.getStartTime();
      long endTime = ep.getEndTime();

      if (ep.isComplete()) {
        if (startTime >= start && endTime <= end) {
          outEvents.add(ep);
        } else if (startTime >= start && startTime <= end) {
          // If the start time is within bounds, but the end time is not, replace with a proxy
          outEvents.add(EventPair.proxy(startTime, end));
        } else if (endTime <= end && endTime >= start) {
          // If the end time is within bounds, but the start time is not, replace with a proxy
          outEvents.add(EventPair.proxy(start, endTime));
        }
      } else if (ep.isOngoing()) {
        // If the event is ongoing, replace with a proxy
        outEvents.add(EventPair.proxy(startTime, end));
      } // Ignore the case where we have an end event but not a start. Just drop that EventPair.
    }
    return outEvents;
  }

  /**
   * Adds a line about a pair of start and finished events to lines.
   *
   * @param prefix Prefix to print for this event pair.
   * @param suffix Suffix to print for this event pair.
   * @param currentMillis The current time in milliseconds.
   * @param offsetMs Offset to remove from calculated time.  Set this to a non-zero value if the
   *     event pair would contain another event.  For example, build time includes parse time, but
   *     to make the events easier to reason about it makes sense to pull parse time out of build
   *     time.
   * @param startEvent The started event.
   * @param finishedEvent The finished event.
   * @param lines The builder to append lines to.
   * @return The amount of time between start and finished if finished is present,
   *    otherwise {@link AbstractConsoleEventBusListener#UNFINISHED_EVENT_PAIR}.
   */
  protected long logEventPair(String prefix,
      Optional<String> suffix,
      long currentMillis,
      long offsetMs,
      @Nullable BuckEvent startEvent,
      @Nullable BuckEvent finishedEvent,
      Optional<Double> progress,
      ImmutableList.Builder<String> lines) {
    if (startEvent == null) {
      return UNFINISHED_EVENT_PAIR;
    }
    EventPair pair = EventPair.builder()
        .setStart(startEvent)
        .setFinish(Optional.<BuckEventExternalInterface>fromNullable(finishedEvent))
        .build();
    return logEventPair(prefix,
        suffix,
        currentMillis,
        offsetMs,
        ImmutableList.of(pair),
        progress,
        lines);
  }

  /**
   * Adds a line about a set of start and finished events to lines.
   *
   * @param prefix Prefix to print for this event pair.
   * @param suffix Suffix to print for this event pair.
   * @param currentMillis The current time in milliseconds.
   * @param offsetMs Offset to remove from calculated time.  Set this to a non-zero value if the
   *     event pair would contain another event.  For example, build time includes parse time, but
   *     to make the events easier to reason about it makes sense to pull parse time out of build
   *     time.
   * @param eventPairs the collection of start/end events to sum up when calculating elapsed time.
   * @param lines The builder to append lines to.
   * @return The summed time between start and finished events if each start event has a matching
   *    finished event, otherwise {@link AbstractConsoleEventBusListener#UNFINISHED_EVENT_PAIR}.
   */
  protected long logEventPair(String prefix,
      Optional<String> suffix,
      long currentMillis,
      long offsetMs,
      Collection<EventPair> eventPairs,
      Optional<Double> progress,
      ImmutableList.Builder<String> lines) {
    if (eventPairs.isEmpty()) {
      return UNFINISHED_EVENT_PAIR;
    }

    long completedRunTimesMs = getTotalCompletedTimeFromEventPairs(eventPairs);
    long currentlyRunningTime = getWorkingTimeFromLastStartUntilNow(eventPairs, currentMillis);
    boolean stillRunning = currentlyRunningTime >= 0;
    String parseLine = (stillRunning ? "[+] " : "[-] ") + prefix + "...";
    long elapsedTimeMs = completedRunTimesMs - offsetMs;
    if (stillRunning) {
      elapsedTimeMs += currentlyRunningTime;
    } else {
      parseLine += "FINISHED ";
      if (progress.isPresent()) {
        progress = Optional.of(1.0);
      }
    }
    parseLine += formatElapsedTime(elapsedTimeMs);
    if (progress.isPresent()) {
      parseLine += " [" + Math.round(progress.get() * 100) + "%]";
    }
    if (suffix.isPresent()) {
      parseLine += " " + suffix.get();
    }
    lines.add(parseLine);
    return stillRunning ? UNFINISHED_EVENT_PAIR : elapsedTimeMs;
  }

  /**
   * Takes a collection of start and finished events. If there are any events that
   * have a start, but no finished time, the collection is considered ongoing.
   * @param eventPairs the collection of event starts/stops.
   * @param currentMillis the current time.
   * @return -1 if all events are completed, otherwise the time elapsed between the latest
   *         event and currentMillis.
   */
  protected static long getWorkingTimeFromLastStartUntilNow(Collection<EventPair> eventPairs,
      long currentMillis) {
    // We examine all events to determine whether we have any incomplete events and also
    // to get the latest timestamp available (start or stop).
    long latestTimestamp = 0L;
    long earliestOngoingStart = Long.MAX_VALUE;
    boolean anyEventIsOngoing = false;
    for (EventPair pair : eventPairs) {
      if (pair.isOngoing()) {
        anyEventIsOngoing = true;
        if (pair.getStartTime() < earliestOngoingStart) {
          latestTimestamp = pair.getStartTime();
        }
      } else if (pair.getEndTime() > latestTimestamp) {
        latestTimestamp = pair.getEndTime();
      }
    }
    // If any events are unpaired, the whole collection is considered ongoing and we return
    // the difference between the latest time in the collection and the current time.
    return anyEventIsOngoing ? currentMillis - latestTimestamp : -1;
  }

  /**
   * Get the summed elapsed time from all matched event pairs. Does not consider unmatched
   * event pairs. Pairs are determined by their {@link com.facebook.buck.event.EventKey}.
   * @param eventPairs a set of paired events (incomplete events are okay).
   * @return the sum of all times between matched event pairs.
   */
  protected static long getTotalCompletedTimeFromEventPairs(Collection<EventPair> eventPairs) {
    long totalTime = 0L;
    // Flatten the event groupings into a timeline, so that we don't over count parallel work.
    RangeSet<Long> timeline = TreeRangeSet.create();
    for (EventPair pair : eventPairs) {
      if (pair.isComplete() && pair.getElapsedTimeMs() > 0) {
        timeline.add(Range.open(pair.getStartTime(), pair.getEndTime()));
      }
    }
    for (Range<Long> range : timeline.asRanges()) {
      totalTime += range.upperEndpoint() - range.lowerEndpoint();
    }
    return totalTime;
  }

  /**
   * Formats a {@link ConsoleEvent} and adds it to {@code lines}.
   */
  protected void formatConsoleEvent(ConsoleEvent logEvent, ImmutableList.Builder<String> lines) {
    String formattedLine = "";
    if (logEvent.containsAnsiEscapeCodes() || logEvent.getLevel().equals(Level.INFO)) {
      formattedLine = logEvent.getMessage();
    } else if (logEvent.getLevel().equals(Level.WARNING)) {
      formattedLine = ansi.asWarningText(logEvent.getMessage());
    } else if (logEvent.getLevel().equals(Level.SEVERE)) {
      formattedLine = ansi.asHighlightedFailureText(logEvent.getMessage());
    }
    if (!formattedLine.isEmpty()) {
      // Split log messages at newlines and add each line individually to keep the line count
      // consistent.
      lines.addAll(Splitter.on("\n").split(formattedLine));
    }
  }

  @Subscribe
  public void commandStartedEvent(CommandEvent.Started startedEvent) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().setCurrentCommand(
          startedEvent.getCommandName(),
          startedEvent.getArgs());
    }
  }

  @Subscribe
  public void projectBuildFileParseStarted(ProjectBuildFileParseEvents.Started started) {
    projectBuildFileParseStarted = started;
  }

  @Subscribe
  public void projectBuildFileParseFinished(ProjectBuildFileParseEvents.Finished finished) {
    projectBuildFileParseFinished = finished;
  }

  @Subscribe
  public void projectGenerationStarted(ProjectGenerationEvent.Started started) {
    projectGenerationStarted = started;
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void projectGenerationProcessedTarget(ProjectGenerationEvent.Processed processed) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didGenerateProjectForTarget();
    }
  }

  @Subscribe
  public void projectGenerationFinished(ProjectGenerationEvent.Finished finished) {
    projectGenerationFinished = finished;
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didFinishProjectGeneration();
    }
  }

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    parseStarted.add(started);
    EventKey eventKey = started.getEventKey();
    if (!buckFilesProcessing.containsKey(eventKey)) {
      buckFilesProcessing.put(eventKey, EventPair.builder().setStart(started).build());
    } else {
      EventPair pair = buckFilesProcessing.get(eventKey);
      buckFilesProcessing.put(eventKey, pair.withStart(started));
    }
  }

  @Subscribe
  public void ruleParseFinished(ParseBuckFileEvent.Finished ruleParseFinished) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didParseBuckRules(ruleParseFinished.getNumRules());
    }
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    parseFinished.add(finished);
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didFinishParsing();
    }
    EventKey eventKey = finished.getEventKey();
    if (!buckFilesProcessing.containsKey(eventKey)) {
      buckFilesProcessing.put(eventKey, EventPair.builder().setFinish(finished).build());
    } else {
      EventPair pair = buckFilesProcessing.get(eventKey);
      buckFilesProcessing.put(eventKey, pair.withFinish(finished));
    }
  }

  @Subscribe
  public void actionGraphStarted(ActionGraphEvent.Started started) {
    actionGraphStarted.add(started);
    EventKey eventKey = started.getEventKey();
    if (!buckFilesProcessing.containsKey(eventKey)) {
      buckFilesProcessing.put(eventKey, EventPair.builder().setStart(started).build());
    } else {
      EventPair pair = buckFilesProcessing.get(eventKey);
      buckFilesProcessing.put(eventKey, pair.withStart(started));
    }
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraphFinished.add(finished);
    EventKey eventKey = finished.getEventKey();
    if (!buckFilesProcessing.containsKey(eventKey)) {
      buckFilesProcessing.put(eventKey, EventPair.builder().setFinish(finished).build());
    } else {
      EventPair pair = buckFilesProcessing.get(eventKey);
      buckFilesProcessing.put(eventKey, pair.withFinish(finished));
    }
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    buildStarted = started;
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didStartBuild();
    }
  }

  @Subscribe
  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    ruleCount = Optional.of(calculated.getNumRules());
    if (progressEstimator.isPresent()) {
      progressEstimator.get().setNumberOfRules(calculated.getNumRules());
    }
  }

  @Subscribe
  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    ruleCount = Optional.of(updated.getNumRules());
  }

  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didStartRule();
    }
    accumulatedTimeTracker.didStartBuildRule(started);
  }

  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didResumeRule();
    }
    accumulatedTimeTracker.didResumeBuildRule(resumed);
  }

  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didSuspendRule();
    }
    accumulatedTimeTracker.didSuspendBuildRule(suspended);
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    if (finished.getStatus() != BuildRuleStatus.CANCELED) {
      if (progressEstimator.isPresent()) {
        progressEstimator.get().didFinishRule();
      }
      numRulesCompleted.getAndIncrement();
    }
    accumulatedTimeTracker.didFinishBuildRule(finished);
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    buildFinished = finished;
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didFinishBuild();
    }
  }

  @Subscribe
  public void testRuleStarted(TestRuleEvent.Started started) {
    accumulatedTimeTracker.didStartTestRule(started);
  }

  @Subscribe
  public void testRuleFinished(TestRuleEvent.Finished finished) {
    accumulatedTimeTracker.didFinishTestRule(finished);
  }

  @Subscribe
  public void installStarted(InstallEvent.Started started) {
    installStarted = started;
  }

  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    installFinished = finished;
  }

  @Subscribe
  public void onHttpArtifactCacheScheduledEvent(HttpArtifactCacheEvent.Scheduled event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      firstHttpCacheUploadScheduled.compareAndSet(null, event);
      httpArtifactUploadsScheduledCount.incrementAndGet();
    }
  }

  @Subscribe
  public void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      httpArtifactUploadsStartedCount.incrementAndGet();
    } else {
      networkStatsKeeper.artifactDownloadedStarted(event);
    }
  }

  @Subscribe
  public void onHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      if (event.wasUploadSuccessful()) {
        httpArtifactUploadedCount.incrementAndGet();
      } else {
        httpArtifactUploadFailedCount.incrementAndGet();
      }
    } else {
      networkStatsKeeper.artifactDownloadFinished(event);
    }
  }

  @Subscribe
  public void onHttpArtifactCacheShutdownEvent(HttpArtifactCacheEvent.Shutdown event) {
    httpShutdownEvent = event;
  }

  @Subscribe
  public void distributedBuildStatus(DistBuildStatusEvent event) {
    if (buildStarted != null)  {
      long elapsed = clock.currentTimeMillis() - buildStarted.getTimestamp();
      long left = event.getStatus().getETAMillis();
      if (elapsed + left > 0) {
        distributedBuildProgress = ((double) elapsed) / (elapsed + left);
      } else {
        distributedBuildProgress = 0;
      }
    }
  }

  protected Optional<String> renderHttpUploads() {
    int scheduled = httpArtifactUploadsScheduledCount.get();
    int complete = httpArtifactUploadedCount.get();
    int failed = httpArtifactUploadFailedCount.get();
    int uploading = httpArtifactUploadsStartedCount.get() - (complete + failed);
    int pending = scheduled - (uploading + complete + failed);
    if (scheduled > 0) {
      return Optional.of(
          String.format(
              "(%d COMPLETE/%d FAILED/%d UPLOADING/%d PENDING)",
              complete,
              failed,
              uploading,
              pending
          ));
    } else {
      return Optional.absent();
    }
  }

  @Override
  public void outputTrace(BuildId buildId) {}

  @Override
  public void close() throws IOException {
  }
}
