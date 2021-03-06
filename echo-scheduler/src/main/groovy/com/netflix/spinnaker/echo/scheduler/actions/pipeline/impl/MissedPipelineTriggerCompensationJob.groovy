/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.echo.scheduler.actions.pipeline.impl

import com.netflix.spinnaker.echo.model.Pipeline
import com.netflix.spinnaker.echo.model.Trigger
import com.netflix.spinnaker.echo.pipelinetriggers.PipelineCache
import com.netflix.spinnaker.echo.pipelinetriggers.orca.OrcaService
import com.netflix.spinnaker.echo.pipelinetriggers.orca.OrcaService.PipelineResponse
import com.netflix.spinnaker.echo.pipelinetriggers.orca.PipelineInitiator
import groovy.util.logging.Slf4j
import org.quartz.CronExpression
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Component
import rx.Observable
import rx.Scheduler
import rx.Subscription

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Finds and executes all pipeline triggers that should have run in the last configured time window during startup.
 * This job will wait until the {@link com.netflix.spinnaker.echo.pipelinetriggers.PipelineCache} has run prior to
 * finding any missed triggers.
 */
@ConditionalOnExpression('${scheduler.enabled:false && scheduler.compensationJob.enabled:false}')
@Component
@Slf4j
class MissedPipelineTriggerCompensationJob implements ApplicationListener<ContextRefreshedEvent> {

  final static int POLLING_INTERVAL_SECONDS = 5

  final Scheduler scheduler
  final PipelineCache pipelineCache
  final OrcaService orcaService
  final CounterService counter
  final PipelineInitiator pipelineInitiator
  final DateContext dateContext

  transient Subscription subscription

  @Autowired
  MissedPipelineTriggerCompensationJob(Scheduler scheduler,
                                       PipelineCache pipelineCache,
                                       OrcaService orcaService,
                                       PipelineInitiator pipelineInitiator,
                                       CounterService counter,
                                       @Value('${scheduler.compensationJob.windowMs:1800000}') long compensationWindowMs,
                                       @Value('${scheduler.cron.timezone:America/Los_Angeles}') String timeZoneId) {
    this(scheduler, pipelineCache, orcaService, pipelineInitiator, counter, compensationWindowMs, timeZoneId, null)
  }

  MissedPipelineTriggerCompensationJob(Scheduler scheduler,
                                       PipelineCache pipelineCache,
                                       OrcaService orcaService,
                                       PipelineInitiator pipelineInitiator,
                                       CounterService counter,
                                       @Value('${scheduler.compensationJob.windowMs:1800000}') long compensationWindowMs,
                                       @Value('${scheduler.cron.timezone:America/Los_Angeles}') String timeZoneId,
                                       DateContext dateContext) {
    this.scheduler = scheduler
    this.pipelineCache = pipelineCache
    this.orcaService = orcaService
    this.pipelineInitiator = pipelineInitiator
    this.counter = counter
    this.dateContext = dateContext ?: DateContext.fromCompensationWindow(timeZoneId, compensationWindowMs)
  }

  @Override
  void onApplicationEvent(ContextRefreshedEvent event) {
    if (subscription == null) {
      subscription = Observable.interval(POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS, scheduler)
        .doOnNext { onPipelineCacheAwait(it) }
        .flatMap { tick -> Observable.just(pipelineCache.pipelines) }
        .doOnError { onPipelineCacheError(it) }
        .retry()
        .subscribe { List<Pipeline> pipelines ->
          if (pipelines.size() == 0) {
            return
          }
          triggerMissedExecutions(pipelines)
          subscription.unsubscribe()
        }
    }
  }

  void onPipelineCacheAwait(long tick) {
    log.info('Waiting for pipeline cache to fill')
  }

  void onPipelineCacheError(Throwable t) {
    log.error("Error waiting for pipeline cache: ${t.message}")
  }

  void triggerMissedExecutions(List<Pipeline> pipelines) {
    log.info('Looking for missed pipeline executions from cron triggers')

    pipelines = pipelines.findAll { !it.disabled }
    List<Trigger> triggers = getEnabledCronTriggers(pipelines)

    List<String> ids = getPipelineConfigIds(pipelines, triggers)
    orcaService.getLatestPipelineExecutions(ids)
      .subscribe({
        onOrcaResponse(it, pipelines, triggers)
      }, { onOrcaError(it) })
  }

  void onOrcaResponse(Collection<PipelineResponse> response, List<Pipeline> pipelines, List<Trigger> triggers) {
    triggers.each { trigger ->
      Pipeline pipeline = pipelines.find { it.triggers*.id.contains(trigger.id) }
      List<Date> executions = response.findAll { it.pipelineConfigId == pipeline.id }.collect {
        // A null value is valid; a pipeline that hasn't started won't get re-triggered.
        it.startTime != null ? new Date(it.startTime) : null
      }

      if (executions.size() == 0) {
        return
      }

      def lastExecution = executions.first()
      if (lastExecution == null) {
        return
      }

      def expr = new CronExpression(trigger.cronExpression)
      expr.timeZone = dateContext.timeZone

      if (missedExecution(expr, lastExecution, dateContext.triggerWindowFloor, dateContext.now)) {
        log.info("Triggering missed execution on pipeline application:${pipeline.application}, pipelineConfigId:${pipeline.id}")
        pipelineInitiator.call(pipeline)
      }
    }
  }

  void onOrcaError(Throwable error) {
    log.error("Error retrieving latest pipeline executions", error)
    counter.increment('orca.errors')
  }

  static List<Trigger> getEnabledCronTriggers(List<Pipeline> pipelines) {
    (List<Trigger>) pipelines
      .collect { it.triggers }
      .flatten()
      .findAll { Trigger it -> it && it.enabled && it.type == Trigger.Type.CRON.toString() }
  }

  /**
   * Missed executions are calculated by any `lastExecution` being between `now` and `now - windowFloor`.
   */
  static boolean missedExecution(CronExpression expr, Date lastExecution, Date windowFloor, Date now) {
    // Quartz works at the minute-level, so given 12:00:00, the next "valid" time will be 12:00:01. Without
    // bumping the lastExecution to the next invalid time, we would double-trigger timely cron executions.
    lastExecution = expr.getNextInvalidTimeAfter(lastExecution)

    if (lastExecution.before(windowFloor)) {
      // The last execution is past the point of no return, as far as the compensation job is concerned.
      return false
    }

    def nextExecution = expr.getNextValidTimeAfter(lastExecution)
    while (true) {
      if (nextExecution.after(windowFloor)) {
        break
      }
      nextExecution = expr.getNextValidTimeAfter(nextExecution)
    }

    return nextExecution.before(now)
  }

  static List<String> getPipelineConfigIds(List<Pipeline> pipelines, List<Trigger> cronTriggers) {
    pipelines.findAll { pipeline ->
      !pipeline.disabled && cronTriggers.find { pipeline.triggers?.contains(it) }
    }.collect { it.id }
  }

  static class DateContext {
    TimeZone timeZone
    Date triggerWindowFloor
    Date now

    static DateContext fromCompensationWindow(String timeZoneId, long compensationWindowMs) {
      Clock clock = Clock.system(ZoneId.of(timeZoneId))
      TimeZone tz = TimeZone.getTimeZone(clock.zone)
      Date triggerWindowFloor = Date.from(Instant.now(clock).minus(compensationWindowMs, ChronoUnit.MILLIS))
      Date now = Date.from(Instant.now(clock))
      return new DateContext(timeZone: tz, triggerWindowFloor: triggerWindowFloor, now: now)
    }
  }
}
