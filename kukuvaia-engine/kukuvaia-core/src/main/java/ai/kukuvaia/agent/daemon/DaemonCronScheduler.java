package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonJobRegistry;
import ai.kukuvaia.agent.daemon.def.DaemonTrigger;
import ai.kukuvaia.agent.daemon.def.PromptRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules every {@code cron:} trigger of the loaded daemon jobs on a dedicated
 * {@link ThreadPoolTaskScheduler} (pool {@code kukuvaia.daemon.scheduler-pool-size}), so long daemon
 * work never blocks Boot's single-threaded default scheduler (e.g. {@code MemoryConsolidationJob}).
 * Firing only submits to {@link DaemonRuntime}; the run itself happens on the runtime's slots.
 * With the kill switch off nothing is scheduled.
 */
@Component
public class DaemonCronScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DaemonCronScheduler.class);

    private final DaemonJobRegistry registry;
    private final DaemonRuntime runtime;
    private final DaemonProperties properties;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private volatile boolean running;

    public DaemonCronScheduler(DaemonJobRegistry registry, DaemonRuntime runtime, DaemonProperties properties) {
        this.registry = registry;
        this.runtime = runtime;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        if (!properties.isEnabled()) {
            log.warn("Daemon disabled (kukuvaia.daemon.enabled=false) — no cron jobs scheduled");
            return;
        }
        scheduler.setPoolSize(properties.getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("kukuvaia-daemon-cron-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        ZoneId zone = properties.zoneId();
        for (DaemonJobDefinition job : registry.all()) {
            for (DaemonTrigger trigger : job.triggers()) {
                if (trigger instanceof DaemonTrigger.Cron cron && job.enabled()) {
                    scheduled.add(scheduler.schedule(() -> fire(job), new CronTrigger(cron.expression(), zone)));
                    log.info("Scheduled daemon job '{}' with cron '{}' ({})", job.name(), cron.expression(), zone);
                }
            }
        }
    }

    void fire(DaemonJobDefinition job) {
        try {
            var submission = runtime.submit(job, new JobRequest(PromptRenderer.render(job.prompt(), Map.of()),
                    Map.of(), "cron", null));
            log.debug("Cron fired '{}': task #{} {}", job.name(), submission.taskId(), submission.status());
        } catch (RuntimeException e) {
            log.error("Cron submission of daemon job '{}' failed: {}", job.name(), e.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        scheduled.forEach(f -> f.cancel(false));
        scheduled.clear();
        if (running && properties.isEnabled()) {
            scheduler.shutdown();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Number of cron registrations (diagnostics / tests). */
    public synchronized int scheduledCount() {
        return scheduled.size();
    }
}
