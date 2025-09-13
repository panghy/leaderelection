package io.github.panghy.leaderelection;

/**
 * Holds the current leader's descriptor for observers.
 */
public record LeaderInfo(ProcessDescriptor leader) {}
