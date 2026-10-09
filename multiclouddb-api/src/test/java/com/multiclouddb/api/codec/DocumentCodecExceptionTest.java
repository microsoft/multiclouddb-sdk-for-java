// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.codec;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static com.multiclouddb.api.codec.DocumentCodecException.Phase.*;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentCodecExceptionTest {
    @Test
    void retainsPhaseAndReasonWithoutRawCausesOrSuppressedDetails() {
        DocumentCodecException failure = new DocumentCodecException(CONSTRUCTION, MAPPER_COPY_FAILED);
        IllegalStateException raw = new IllegalStateException("customer-secret");
        assertThrows(IllegalStateException.class, () -> failure.initCause(raw));
        failure.addSuppressed(raw);
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(CONSTRUCTION, failure.phase());
        assertEquals(MAPPER_COPY_FAILED, failure.reason());
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains("customer-secret"));
        assertTrue(failure.getMessage().contains("CONSTRUCTION/MAPPER_COPY_FAILED"));
    }

    @Test
    void rejectsMissingIdentifiers() {
        assertThrows(NullPointerException.class, () -> new DocumentCodecException(null, INVALID_ARGUMENT));
        assertThrows(NullPointerException.class, () -> new DocumentCodecException(ENCODE, null));
    }
}
