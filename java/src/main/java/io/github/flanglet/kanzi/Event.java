/*
 * Kanzi is a modern, modular, portable, and efficient lossless data compressor.
 *
 * Copyright (C) 2011-2025 Frederic Langlet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.flanglet.kanzi;

/**
 * This class represents events that occur during compression and decompression
 * processes. Each event includes attributes such as type, size, hash, and
 * timestamp.
 */
public class Event {

    /**
     * Enum representing the types of events that can occur.
     */
    public enum Type {
        /**
         * Beginning of compression
         */
        COMPRESSION_START,

        /**
         * Beginning of decompression
         */
        DECOMPRESSION_START,

        /**
         * Beginning of transform
         */
        BEFORE_TRANSFORM,

        /**
         * End of transform
         */
        AFTER_TRANSFORM,

        /**
         * Beginning of entropy
         */
        BEFORE_ENTROPY,

        /**
         * End of entropy
         */
        AFTER_ENTROPY,

        /**
         * End of compression
         */
        COMPRESSION_END,

        /**
         * End of dcompression
         */
        DECOMPRESSION_END,

        /**
         * End of header decoding
         */
        AFTER_HEADER_DECODING,

        /**
         * Block informartion
         */
        BLOCK_INFO
    }

    /**
     * Enum representing the types of hash used in the events.
     */
    public enum HashType {
        /**
         * No hash
         */
        NO_HASH,

        /**
         * 32 bit hash
         */

        SIZE_32,
        /**
         * 64 bit hash
         */
        SIZE_64
    }

    private final int id;
    private final long size;
    private final long hash;
    private final Type type;
    private final HashType hashType;
    private final long time;
    private final String msg;

    /**
     * Constructs an Event with the specified type, id, and size, with no hash.
     *
     * @param type
     *            the type of event
     * @param id
     *            the event id
     * @param size
     *            the size of the event
     */
    public Event(Type type, int id, long size) {
        this(type, id, size, 0, HashType.NO_HASH);
    }

    /**
     * Constructs an Event with the specified type, id, and message.
     *
     * @param type
     *            the type of event
     * @param id
     *            the event id
     * @param msg
     *            the event message
     */
    public Event(Type type, int id, String msg) {
        this(type, id, msg, 0);
    }

    /**
     * Constructs an Event with the specified type, id, message, and time.
     *
     * @param type
     *            the type of event
     * @param id
     *            the event id
     * @param msg
     *            the event message
     * @param time
     *            the event timestamp
     */
    public Event(Type type, int id, String msg, long time) {
        this.id = id;
        this.size = 0L;
        this.hash = 0;
        this.hashType = HashType.NO_HASH;
        this.type = type;
        this.time = (time > 0) ? time : System.nanoTime();
        this.msg = msg;
    }

    /**
     * Constructs an Event with the specified type, id, size, hash, and hash type.
     *
     * @param type
     *            the type of event
     * @param id
     *            the event id
     * @param size
     *            the size of the event
     * @param hash
     *            the hash of the event
     * @param hashType
     *            the type of hash used
     */
    public Event(Type type, int id, long size, long hash, HashType hashType) {
        this(type, id, size, hash, hashType, 0);
    }

    /**
     * Constructs an Event with the specified type, id, size, hash, hash type, and
     * time.
     *
     * @param type
     *            the type of event
     * @param id
     *            the event id
     * @param size
     *            the size of the event
     * @param hash
     *            the hash of the event
     * @param hashType
     *            the type of hash used
     * @param time
     *            the event timestamp
     */
    public Event(Type type, int id, long size, long hash, HashType hashType, long time) {
        this.id = id;
        this.size = size;
        this.hash = hash;
        this.hashType = hashType;
        this.type = type;
        this.time = (time > 0) ? time : System.nanoTime();
        this.msg = null;
    }

    /**
     * Returns the event id.
     *
     * @return the event id
     */
    public int getId() {
        return this.id;
    }

    /**
     * Returns the size of the event.
     *
     * @return the event size
     */
    public long getSize() {
        return this.size;
    }

    /**
     * Returns the timestamp of the event.
     *
     * @return the event timestamp
     */
    public long getTime() {
        return this.time;
    }

    /**
     * Returns the hash of the event, or 0 if no hash is used.
     *
     * @return the event hash
     */
    public long getHash() {
        return (this.hashType == HashType.NO_HASH) ? 0 : this.hash;
    }

    /**
     * Returns the type of hash used in the event.
     *
     * @return the event hash type
     */
    public HashType getHashType() {
        return this.hashType;
    }

    /**
     * Returns the type of the event.
     *
     * @return the event type
     */
    public Type getType() {
        return this.type;
    }

    /**
     * Returns a string representation of the event.
     *
     * @return a string representation of the event
     */
    @Override
    public String toString() {
        if (this.msg != null) {
            return this.msg;
        }
        StringBuilder sb = new StringBuilder(200);
        sb.append("{ \"type\":\"").append(this.getType()).append("\"");
        if (this.id >= 0) {
            sb.append(", \"id\":").append(this.getId());
        }
        sb.append(", \"size\":").append(this.getSize());
        sb.append(", \"time\":").append(this.getTime());
        if (this.hashType == HashType.SIZE_32) {
            sb.append(", \"hash\":\"").append(Integer.toHexString((int) this.getHash())).append("\"");
        } else if (this.hashType == HashType.SIZE_64) {
            sb.append(", \"hash\":\"").append(Long.toHexString(this.getHash())).append("\"");
        }
        sb.append(" }");
        return sb.toString();
    }
}
