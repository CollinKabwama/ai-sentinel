package dev.aisentinel.autoconfigure.pilot;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.MonitorOnlyEnforcementHandler;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.pilot.GatedPilotObservationRecorder;
import dev.aisentinel.core.pilot.LocalPilotEvidenceSession;
import dev.aisentinel.core.pilot.NoopPilotObservationRecorder;
import dev.aisentinel.core.pilot.PilotConfigSnapshot;
import dev.aisentinel.core.pilot.PilotObservation;
import dev.aisentinel.core.pilot.PilotObservationRecorder;
import dev.aisentinel.core.pilot.PilotPseudonymizer;
import dev.aisentinel.core.pilot.PilotSoftwareVersion;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * MONITOR-mode pilot evidence wiring for the Spring Boot / Servlet reference surface.
 * <p>
 * Enabled only when {@code ai.sentinel.pilot.enabled=true}. Startup validation rejects
 * unsupported configurations rather than silently collecting under unsafe wiring.
 */
@Configuration
public class PilotEvidenceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PilotEvidenceConfiguration.class);

    private LocalPilotEvidenceSession activeSession;

    @Bean
    @ConditionalOnMissingBean(PilotObservationRecorder.class)
    public PilotObservationRecorder pilotObservationRecorder(
        SentinelProperties props,
        EnforcementHandler enforcementHandler
    ) throws IOException {
        SentinelProperties.Pilot pilot = props.getPilot();
        if (pilot == null || !pilot.isEnabled()) {
            return NoopPilotObservationRecorder.INSTANCE;
        }

        validatePilotSafeConfiguration(props, enforcementHandler);

        String sessionId = pilot.getSessionId() == null || pilot.getSessionId().isBlank()
            ? UUID.randomUUID().toString()
            : pilot.getSessionId().trim();
        Path outputDir = Path.of(pilot.getOutputDirectory().trim());

        PilotConfigSnapshot config = new PilotConfigSnapshot(
            PilotObservation.RUNTIME_MODE_MONITOR,
            props.getStatistical().getBaselineUpdatePolicy().name(),
            "composite",
            PilotSoftwareVersion.resolve(),
            PilotSoftwareVersion.resolve(),
            FeatureSchema.VERSION_ID,
            PilotObservation.SCHEMA_VERSION,
            PilotObservation.EVIDENCE_CLASS,
            "threshold-policy",
            props.getThresholdModerate(),
            props.getThresholdElevated(),
            props.getThresholdHigh(),
            props.getThresholdCritical()
        );

        PilotPseudonymizer pseudonymizer = new PilotPseudonymizer(pilot.getPseudonymizationSecret());
        LocalPilotEvidenceSession session = new LocalPilotEvidenceSession(
            outputDir, sessionId, config, pseudonymizer);
        this.activeSession = session;

        log.info(
            "AI-Sentinel MONITOR pilot evidence collection active (sessionId={}, observationSchemaVersion={}, configDigest={})",
            session.pilotSessionId(),
            PilotObservation.SCHEMA_VERSION,
            session.configDigest());

        return new GatedPilotObservationRecorder(
            () -> props.getMode() == SentinelProperties.Mode.MONITOR,
            session
        );
    }

    @PreDestroy
    public void finalizePilotSession() {
        if (activeSession == null) {
            return;
        }
        try {
            activeSession.finalizeSession();
            log.info("AI-Sentinel MONITOR pilot evidence session finalized (sessionId={})",
                activeSession.pilotSessionId());
        } catch (Exception e) {
            log.warn("Pilot evidence finalization failed: {}: {}",
                e.getClass().getSimpleName(), e.getMessage());
        } finally {
            activeSession = null;
        }
    }

    static void validatePilotSafeConfiguration(SentinelProperties props, EnforcementHandler handler) {
        if (props.getMode() != SentinelProperties.Mode.MONITOR) {
            throw new IllegalStateException(
                "ai.sentinel.pilot.enabled requires ai.sentinel.mode=MONITOR "
                    + "(pilot evidence collection is MONITOR-only)");
        }
        if (props.getDistributed() != null && props.getDistributed().isTrainingPublishEnabled()) {
            throw new IllegalStateException(
                "ai.sentinel.pilot.enabled requires ai.sentinel.distributed.training-publish-enabled=false");
        }
        SentinelProperties.Pilot pilot = props.getPilot();
        if (pilot.getOutputDirectory() == null || pilot.getOutputDirectory().isBlank()) {
            throw new IllegalStateException(
                "ai.sentinel.pilot.output-directory is required when pilot evidence collection is enabled");
        }
        if (pilot.getPseudonymizationSecret() == null || pilot.getPseudonymizationSecret().isBlank()) {
            throw new IllegalStateException(
                "ai.sentinel.pilot.pseudonymization-secret is required when pilot evidence collection is enabled");
        }
        // Validate secret length without echoing the value.
        try {
            new PilotPseudonymizer(pilot.getPseudonymizationSecret());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "ai.sentinel.pilot.pseudonymization-secret is invalid for pilot pseudonymization", e);
        }
        if (!(handler instanceof MonitorOnlyEnforcementHandler)) {
            throw new IllegalStateException(
                "ai.sentinel.pilot.enabled requires the default MONITOR MonitorOnlyEnforcementHandler wiring; "
                    + "custom EnforcementHandler beans are unsupported for pilot evidence collection");
        }
    }
}
