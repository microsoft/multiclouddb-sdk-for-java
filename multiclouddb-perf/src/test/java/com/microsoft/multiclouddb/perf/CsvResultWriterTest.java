package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvResultWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsAppendingToAnOlderSchema() throws Exception {
        Path csv = tempDir.resolve("old.csv");
        Files.writeString(csv, "run_id,timestamp_utc,provider\n");

        assertThrows(IllegalStateException.class, () -> new CsvResultWriter(csv));
    }
}
