// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.base.GeneratorBase;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.codec.DocumentCodecException;
import com.multiclouddb.api.document.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.multiclouddb.api.codec.DocumentCodecException.Phase.ENCODE;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.*;

/** Collects serializer tokens directly, without binding them through a Map deserializer. */
final class DocumentGenerator extends GeneratorBase {
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final StreamWriteConstraints constraints;
    private Document result;
    private boolean rootStarted;
    private boolean failed;

    DocumentGenerator(ObjectMapper mapper) {
        super(mapper.getFactory().getGeneratorFeatures(), mapper);
        constraints = mapper.getFactory().streamWriteConstraints();
    }

    Document result() {
        if (failed || result == null || !frames.isEmpty()) {
            throw fail(INVALID_STRUCTURE);
        }
        return result;
    }

    @Override
    public StreamWriteConstraints streamWriteConstraints() {
        return constraints;
    }

    @Override
    public void writeStartObject() throws IOException {
        beginValue(true);
        _writeContext = _writeContext.createChildObjectContext();
        validateNestingDepth();
        frames.push(new Frame(true));
    }

    @Override
    public void writeStartArray() throws IOException {
        beginValue(false);
        _writeContext = _writeContext.createChildArrayContext();
        validateNestingDepth();
        frames.push(new Frame(false));
    }

    private void validateNestingDepth() throws StreamConstraintsException {
        try {
            constraints.validateNestingDepth(_writeContext.getNestingDepth());
            if (_writeContext.getNestingDepth() > 128) throw fail(UNSUPPORTED_OUTPUT);
        } catch (StreamConstraintsException failure) {
            failed = true;
            frames.clear();
            throw failure;
        }
    }

    @Override
    public void writeEndObject() {
        checkActive();
        if (frames.isEmpty() || !frames.peek().object || frames.peek().name != null) {
            throw fail(INVALID_STRUCTURE);
        }
        ObjectValue value = ObjectValue.of(frames.pop().fields);
        _writeContext = _writeContext.getParent();
        if (frames.isEmpty()) {
            result = Document.of(value);
        } else {
            append(value);
        }
    }

    @Override
    public void writeEndArray() {
        checkActive();
        if (frames.isEmpty() || frames.peek().object) {
            throw fail(INVALID_STRUCTURE);
        }
        ArrayValue value = ArrayValue.of(frames.pop().items);
        _writeContext = _writeContext.getParent();
        append(value);
    }

    @Override
    public void writeFieldName(String name) throws IOException {
        checkActive();
        if (name == null || frames.isEmpty() || !frames.peek().object || frames.peek().name != null) {
            throw fail(INVALID_STRUCTURE);
        }
        if (frames.peek().fields.containsKey(name)) {
            throw fail(DUPLICATE_FIELD);
        }
        frames.peek().name = name;
        _writeContext.writeFieldName(name);
    }

    private void beginValue(boolean objectRoot) {
        checkActive();
        if (frames.isEmpty()) {
            if (!objectRoot) throw fail(INVALID_ROOT);
            if (rootStarted) throw fail(INVALID_STRUCTURE);
            rootStarted = true;
        } else if (frames.peek().object && frames.peek().name == null) {
            throw fail(INVALID_STRUCTURE);
        }
        _writeContext.writeValue();
    }

    private void append(DocumentValue value) {
        Frame frame = frames.peek();
        if (frame == null) throw fail(INVALID_STRUCTURE);
        if (frame.object) {
            frame.fields.put(frame.name, value);
            frame.name = null;
        } else {
            frame.items.add(value);
        }
    }

    private void scalar(DocumentValue value) {
        beginValue(false);
        append(value);
    }

    private void number(Number value) {
        if (value != null && _cfgNumbersAsStrings) {
            // Text-generator formatting must not silently change token semantics.
            throw fail(UNSUPPORTED_OUTPUT);
        }
        try {
            scalar(value == null ? NullValue.INSTANCE : NumberValue.of(value));
        } catch (IllegalArgumentException invalid) {
            throw fail(UNSUPPORTED_OUTPUT);
        }
    }

    @Override public void writeString(String value) {
        scalar(value == null ? NullValue.INSTANCE : new StringValue(value));
    }
    @Override public void writeString(char[] value, int offset, int length) {
        scalar(new StringValue(new String(value, offset, length)));
    }
    @Override public void writeBoolean(boolean value) { scalar(new BooleanValue(value)); }
    @Override public void writeNull() { scalar(NullValue.INSTANCE); }
    @Override public void writeNumber(short value) { number(value); }
    @Override public void writeNumber(int value) { number(value); }
    @Override public void writeNumber(long value) { number(value); }
    @Override public void writeNumber(float value) { number(value); }
    @Override public void writeNumber(double value) { number(value); }
    @Override public void writeNumber(BigInteger value) { number(value); }
    @Override public void writeNumber(BigDecimal value) { number(value); }

    @Override
    public void writeUTF8String(byte[] value, int offset, int length) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value, offset, length)).toString();
            scalar(new StringValue(text));
        } catch (CharacterCodingException invalid) {
            throw fail(UNSUPPORTED_OUTPUT);
        }
    }

    @Override public void writeString(Reader reader, int length) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeNumber(String value) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeNumber(char[] value, int offset, int length) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRaw(String value) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRaw(String value, int offset, int length) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRaw(char[] value, int offset, int length) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRaw(char value) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRawValue(String value) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRawValue(String value, int offset, int length) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRawValue(char[] value, int offset, int length) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeRawUTF8String(byte[] value, int offset, int length) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeBinary(Base64Variant variant, byte[] value, int offset, int length) {
        throw fail(UNSUPPORTED_OUTPUT);
    }
    @Override public int writeBinary(Base64Variant variant, InputStream value, int length) {
        throw fail(UNSUPPORTED_OUTPUT);
    }
    @Override public void writeEmbeddedObject(Object value) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeObjectId(Object value) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeObjectRef(Object value) { throw fail(UNSUPPORTED_OUTPUT); }
    @Override public void writeTypeId(Object value) { throw fail(UNSUPPORTED_OUTPUT); }

    @Override public void flush() { }
    @Override public void close() { _closed = true; _releaseBuffers(); }
    @Override protected void _releaseBuffers() { frames.clear(); }
    @Override protected void _verifyValueWrite(String message) { beginValue(false); }

    private void checkActive() {
        if (failed || _closed || result != null) throw fail(INVALID_STRUCTURE);
    }

    private DocumentCodecException fail(DocumentCodecException.Reason reason) {
        failed = true;
        frames.clear();
        return new DocumentCodecException(ENCODE, reason);
    }

    private static final class Frame {
        final boolean object;
        final Map<String, DocumentValue> fields;
        final List<DocumentValue> items;
        String name;

        Frame(boolean object) {
            this.object = object;
            fields = object ? new LinkedHashMap<>() : null;
            items = object ? null : new ArrayList<>();
        }
    }
}
