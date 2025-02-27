/*
 * Copyright 2020, Seqera Labs
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.upplication.s3fs.util;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@SuppressWarnings("unchecked")
public class S3MultipartOptions<V extends S3MultipartOptions> {

    private static final Logger log = LoggerFactory.getLogger(S3MultipartOptions.class);

    public static final int DEFAULT_CHUNK_SIZE = 20 << 20;  // 20 MB

    /**
     * Upload chunk max size
     */
    private int chunkSize;

    /**
     * Maximum number of threads allowed
     */
    private int maxThreads;

    /**
     * Maximum number of attempts to upload a chunk in a multiparts upload process
     */
    private int maxAttempts;

    /**
     * Time (milliseconds) to wait after a failed upload to retry a chunk upload
     */
    private long retrySleep;


    /**
     * initialize default values
     */
    {
        retrySleep = 500;
        chunkSize = DEFAULT_CHUNK_SIZE;
        maxAttempts = 5;
        maxThreads = Runtime.getRuntime().availableProcessors() *3;
    }

    public S3MultipartOptions() {

    }

    public S3MultipartOptions(Properties props) {
        setMaxThreads(props.getProperty("upload_max_threads"));
        setChunkSize(props.getProperty("upload_chunk_size"));
        setMaxAttempts(props.getProperty("upload_max_attempts"));
        setRetrySleep(props.getProperty("upload_retry_sleep"));
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getChunkSize( long objectSize ) {
        final int MAX_PARTS = 10_000;
        long numOfParts = objectSize / chunkSize;
        if( numOfParts > MAX_PARTS ) {
            chunkSize = (int) objectSize / MAX_PARTS;
        }

        return chunkSize;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getRetrySleep() {
        return retrySleep;
    }


    public V setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
        return (V)this;
    }

    public V setChunkSize(String chunkSize) {
        if( chunkSize==null )
            return (V)this;

        try {
            setChunkSize(Integer.parseInt(chunkSize));
        }
        catch( NumberFormatException e ) {
            log.warn("Not a valid AWS S3 multipart upload chunk size: `{}` -- Using default", chunkSize);
        }
        return (V)this;
    }

    public V setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        return (V)this;
    }

    public V setMaxThreads(String maxThreads) {
        if( maxThreads==null )
            return (V)this;

        try {
            setMaxThreads(Integer.parseInt(maxThreads));
        }
        catch( NumberFormatException e ) {
            log.warn("Not a valid AWS S3 multipart upload max threads: `{}` -- Using default", maxThreads);
        }
        return (V)this;
    }

    public V setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return (V)this;
    }

    public V setMaxAttempts(String maxAttempts) {
        if( maxAttempts == null )
            return (V)this;

        try {
            this.maxAttempts = Integer.parseInt(maxAttempts);
        }
        catch(NumberFormatException e ) {
            log.warn("Not a valid AWS S3 multipart upload max attempts value: `{}` -- Using default", maxAttempts);
        }
        return (V)this;
    }

    public V setRetrySleep( long retrySleep ) {
        this.retrySleep = retrySleep;
        return (V)this;
    }

    public V setRetrySleep( String retrySleep ) {
        if( retrySleep == null )
            return (V)this;

        try {
            this.retrySleep = Long.parseLong(retrySleep);
        }
        catch (NumberFormatException e ) {
            log.warn("Not a valid AWS S3 multipart upload retry sleep value: `{}` -- Using default", retrySleep);
        }
        return (V)this;
    }

    public long getRetrySleepWithAttempt( int attempt ) {
        return retrySleep * ( 1 << (attempt-1) );
    }

    @Override
    public String toString() {
        return "chunkSize=" + chunkSize +
                "; maxThreads=" + maxThreads +
                "; maxAttempts=" + maxAttempts +
                "; retrySleep=" + retrySleep;
    }

}
