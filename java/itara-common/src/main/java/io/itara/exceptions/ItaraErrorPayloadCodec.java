package io.itara.exceptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes {@link ItaraErrorPayload} to and from a fixed,
 * dependency-free binary format.
 *
 * Every serializer must be able to handle the error payload unconditionally,
 * regardless of its message-format specialization (ADR 0020). A serializer
 * whose primary strategy is generic over arbitrary objects — JSON, Java
 * object serialization — already satisfies this for free and has no reason
 * to use this class. A serializer built around a structural message format
 * — protobuf, and any future one like it — cannot: the error payload is a
 * plain DTO, not a message-format-generated type, so there is nothing for
 * that serializer's generic business-payload path to do with it. This
 * class exists so every such serializer reuses one implementation instead
 * of each inventing its own encoding.
 *
 * This is deliberately not tied to any message format's own wire
 * conventions (not real Protocol Buffers, not JSON) — it is a small,
 * self-contained format private to Itara, understood only by this class
 * on both the encode and decode side. Any two Itara peers exchanging an
 * error payload through a non-generic serializer are exchanging bytes
 * produced and consumed by this exact class, nothing else.
 *
 * Wire format — a sequence of fields, each:
 *
 *   [1 byte  tag      ]
 *   [1 byte  null-flag]  0 = null (nothing more follows for this field)
 *                        1 = present (length + value follow)
 *   [4 bytes length   ]  big-endian signed int, UTF-8 byte count — only
 *                        present when null-flag = 1
 *   [N bytes value    ]  UTF-8 bytes — only present when null-flag = 1
 *
 * Every field, including the two that are never actually null in practice
 * (errorKind, remoteExceptionClass), carries the same null-flag byte. The
 * cost is a handful of bytes; the benefit is one uniform, unambiguous
 * per-field shape with no special cases to get wrong.
 *
 * Current tags:
 *   1 = errorKind             (ErrorKind.name(), not ordinal — see below)
 *   2 = remoteExceptionClass
 *   3 = message                (nullable)
 *
 * Forward/backward compatibility:
 *
 *   Decoding never fails because of an unrecognized tag. Every field is
 *   fully self-describing (its own null-flag, and length when present),
 *   so a decoder encountering a tag it doesn't recognize still reads
 *   exactly the right number of bytes to skip past it correctly and
 *   continues — bytes written by a newer encoder with an extra field are
 *   silently ignored by an older decoder. Symmetrically, bytes written by
 *   an older encoder simply never contain a newer tag, so a newer decoder
 *   leaves that field at its default (null) — nothing to read, nothing to
 *   fail on.
 *
 *   errorKind is encoded by name, not ordinal, so a reordering of the
 *   ErrorKind enum's declared constants can never silently change what a
 *   previously-encoded payload decodes to. If a future ErrorKind constant
 *   is added and its name reaches a decoder built against an older jar
 *   that doesn't have it, ErrorKind.valueOf() has nothing to resolve to —
 *   this decoder does not throw in that case; it falls back to
 *   ErrorKind.TRANSPORT (the safest default: treat the unrecognized kind
 *   as an infrastructure failure) rather than raising a decode error over
 *   what is, from the caller's point of view, still a definite failure
 *   that must be reported one way or another.
 */
public class ItaraErrorPayloadCodec {

    private static final byte TAG_ERROR_KIND = 1;
    private static final byte TAG_REMOTE_EXCEPTION_CLASS = 2;
    private static final byte TAG_MESSAGE = 3;

    private static final byte NULL_FLAG_ABSENT = 0;
    private static final byte NULL_FLAG_PRESENT = 1;

    private ItaraErrorPayloadCodec() {
    }

    /**
     * Encodes an error payload to bytes.
     *
     * @param payload the payload to encode; must not be null
     * @return the encoded bytes
     */
    public static byte[] encode(ItaraErrorPayload payload) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeField(out, TAG_ERROR_KIND,
                    payload.getErrorKind() != null ? payload.getErrorKind().name() : null);
            writeField(out, TAG_REMOTE_EXCEPTION_CLASS, payload.getRemoteExceptionClass());
            writeField(out, TAG_MESSAGE, payload.getMessage());
        } catch (IOException e) {
            // ByteArrayOutputStream/DataOutputStream never actually throw
            // IOException in practice — there is no underlying resource
            // that can fail. Wrapped rather than declared so callers on
            // both the JSON/Java-style generic path and this one see a
            // consistent unchecked failure mode.
            throw new IllegalStateException(
                    "[Itara] Unexpected I/O failure encoding ItaraErrorPayload", e);
        }
        return buffer.toByteArray();
    }

    /**
     * Decodes bytes produced by {@link #encode} back into an error payload.
     *
     * @param bytes the encoded bytes
     * @return the decoded payload
     * @throws IllegalArgumentException if the bytes are structurally malformed
     *         (truncated mid-field, negative or overrunning length, etc.)
     */
    public static ItaraErrorPayload decode(byte[] bytes) {
        ItaraRemoteException.ErrorKind errorKind = null;
        String remoteExceptionClass = null;
        String message = null;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            while (in.available() > 0) {
                byte tag = in.readByte();
                String value = readField(in);

                switch (tag) {
                    case TAG_ERROR_KIND:
                        errorKind = (value != null) ? resolveErrorKind(value) : null;
                        break;
                    case TAG_REMOTE_EXCEPTION_CLASS:
                        remoteExceptionClass = value;
                        break;
                    case TAG_MESSAGE:
                        message = value;
                        break;
                    default:
                        // Unknown tag from a newer encoder — readField()
                        // already consumed exactly its bytes above, so
                        // there is nothing further to do; move on to the
                        // next field.
                        break;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "[Itara] Malformed ItaraErrorPayload bytes: " + e.getMessage(), e);
        }

        return new ItaraErrorPayload(errorKind, remoteExceptionClass, message);
    }

    private static void writeField(DataOutputStream out, byte tag, String value) throws IOException {
        out.writeByte(tag);
        if (value == null) {
            out.writeByte(NULL_FLAG_ABSENT);
        } else {
            out.writeByte(NULL_FLAG_PRESENT);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(valueBytes.length);
            out.write(valueBytes);
        }
    }

    private static String readField(DataInputStream in) throws IOException {
        byte nullFlag = in.readByte();
        if (nullFlag == NULL_FLAG_ABSENT) {
            return null;
        }
        int length = in.readInt();
        byte[] valueBytes = new byte[length];
        in.readFully(valueBytes);
        return new String(valueBytes, StandardCharsets.UTF_8);
    }

    private static ItaraRemoteException.ErrorKind resolveErrorKind(String name) {
        try {
            return ItaraRemoteException.ErrorKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            // A future ErrorKind constant, unknown to this jar's enum.
            // Treat as the safest default rather than failing to decode
            // what is, either way, a definite failure that must reach
            // the caller somehow.
            return ItaraRemoteException.ErrorKind.TRANSPORT;
        }
    }
}
