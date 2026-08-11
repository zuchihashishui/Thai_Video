package com.thaivideo.app.model;

import java.nio.file.Path;

/**
 * Kết quả trả về sau khi convert thành công.
 */
public class ConversionResult {

    private final Path outputPath;
    private final long durationMs;
    private final String commandLine;
    private final int exitCode;
    private final String aspect;
    private final String resolution;

    public ConversionResult(Path outputPath,
                            long durationMs,
                            String commandLine,
                            int exitCode,
                            String aspect,
                            String resolution) {
        this.outputPath = outputPath;
        this.durationMs = durationMs;
        this.commandLine = commandLine;
        this.exitCode = exitCode;
        this.aspect = aspect;
        this.resolution = resolution;
    }

    public Path getOutputPath() { return outputPath; }
    public long getDurationMs() { return durationMs; }
    public String getCommandLine() { return commandLine; }
    public int getExitCode() { return exitCode; }
    public String getAspect() { return aspect; }
    public String getResolution() { return resolution; }
}
