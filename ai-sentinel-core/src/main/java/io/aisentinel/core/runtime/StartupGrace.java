package io.aisentinel.core.runtime;

/**
 * Indicates whether the JVM is still within a configurable startup grace window.
 * During grace, the pipeline should apply reduced enforcement (typically MONITOR-only).
 */
public interface StartupGrace {

    StartupGrace NEVER = () -> false;

    boolean isGraceActive();
}
