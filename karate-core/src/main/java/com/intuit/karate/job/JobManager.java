/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.job;

import com.intuit.karate.FileUtils;
import com.intuit.karate.Json;
import com.intuit.karate.JsonUtils;
import com.intuit.karate.http.HttpServer;
import com.intuit.karate.http.Request;
import com.intuit.karate.http.ResourceType;
import com.intuit.karate.http.Response;
import com.intuit.karate.http.ServerHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pthomas3
 */
public class JobManager<T> implements ServerHandler {

    protected static final Logger logger = LoggerFactory.getLogger(JobManager.class);

    public static final String KARATE_JOB_HEADER = "karate-job";
    public static final String EXECUTOR_DIR = "executorDir";

    public final JobConfig<T> config;
    private final String basePath;
    private final File ZIP_FILE;
    public final String jobId;
    public final String jobUrl;
    public final HttpServer server;

    private final Map<String, JobChunk<T>> chunks = new HashMap();
    private final ArrayBlockingQueue<JobChunk> queue;
    private final AtomicInteger chunkCounter = new AtomicInteger();
    private final AtomicInteger executorCounter = new AtomicInteger(1);

    public JobManager(JobConfig config) {
        this.config = config;
        jobId = System.currentTimeMillis() + "";
        basePath = FileUtils.getBuildDir() + File.separator + jobId;
        ZIP_FILE = new File(basePath + ".zip");
        JobUtils.zip(new File(config.getSourcePath()), ZIP_FILE);
        logger.info("created zip archive: {}", ZIP_FILE);
        server = new HttpServer(config.getPort(), this);
        jobUrl = "http://" + config.getHost() + ":" + server.getPort();
        queue = new ArrayBlockingQueue(config.getExecutorCount());
    }

    public <T> CompletableFuture<T> addChunk(T value) {
        try {
            String chunkId = chunkCounter.incrementAndGet() + "";
            JobChunk jc = new JobChunk(chunkId, value);
            synchronized (chunks) {
                chunks.put(jc.getId(), jc);
            }
            logger.debug("waiting for queue: {}", jc);
            queue.put(jc);
            logger.debug("queue put: {}", jc);
            return jc.getFuture();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public void startExecutors() {
        try {
            config.startExecutors(jobId, jobUrl);
        } catch (Exception e) {
            logger.error("failed to start executors: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }    

    @Override
    public Response handle(Request request) {
        if (!request.getMethod().equals("POST")) {
            if (request.getPath().equals("healthcheck")) {
                return Response.OK;
            }
            return errorResponse(request + " not supported");
        }
        String jobHeader = request.getHeader(KARATE_JOB_HEADER);
        JobMessage req = toJobMessage(jobHeader);
        if (req.method == null) {
            return errorResponse("'method' required in 'karate-job' header (json)");
        }
        ResourceType rt = request.getResourceType();
        if (rt != null && rt.isBinary()) {
            req.setBytes(request.getBody());
        } else {
            req.setBody((Map) request.getBodyConverted());
        }
        JobMessage res = handle(req);
        Response response = new Response(200);
        Json json = Json.object();
        json.set("method", res.method);
        json.set("jobId", jobId);
        if (res.getExecutorId() != null) {
            json.set("executorId", res.getExecutorId());
        }
        if (res.getChunkId() != null) {
            json.set("chunkId", res.getChunkId());
        }
        response.setHeader(KARATE_JOB_HEADER, json.toString());
        if (res.getBytes() != null) {
            response.setBody(res.getBytes());
            response.setContentType(ResourceType.BINARY.contentType);
        } else if (res.getBody() != null) {
            byte[] bytes = JsonUtils.toJsonBytes(res.getBody());
            response.setBody(bytes);
            response.setContentType(ResourceType.JSON.contentType);
        }
        return response;
    }

    private Response errorResponse(String message) {
        Response response = new Response(400);
        response.setBody(message);
        return response;
    }

    public static JobMessage toJobMessage(String value) {
        Json json = Json.of(value);
        String method = json.getOptional("method");
        JobMessage jm = new JobMessage(method);
        jm.setJobId(json.getOptional("jobId"));
        jm.setExecutorId(json.getOptional("executorId"));
        jm.setChunkId(json.getOptional("chunkId"));
        return jm;
    }

    private JobMessage handle(JobMessage jm) {
        String method = jm.method;
        switch (method) {
            case "error":
                dumpLog(jm);
                return new JobMessage("error");
            case "heartbeat":
                logger.info("hearbeat: {}", jm);
                return new JobMessage("heartbeat");
            case "download":
                logger.info("download: {}", jm);
                JobMessage download = new JobMessage("download");
                download.setBytes(getDownload());
                int executorId = executorCounter.getAndIncrement();
                download.setExecutorId(executorId + "");
                return download;
            case "init":
                logger.info("init: {}", jm);
                JobMessage init = new JobMessage("init");
                init.put("startupCommands", config.getStartupCommands());
                init.put("shutdownCommands", config.getShutdownCommands());
                init.put("environment", config.getEnvironment());
                init.put(EXECUTOR_DIR, config.getExecutorDir());
                return init;
            case "next":
                logger.info("next: {}", jm);
                JobChunk<T> jc = queue.poll();
                if (jc == null) {
                    logger.info("no more chunks, server responding with 'stop' message");
                    return new JobMessage("stop");
                }
                jc.setStartTime(System.currentTimeMillis());
                jc.setJobId(jobId);
                jc.setExecutorId(jm.getExecutorId());
                String executorDir = jm.get(EXECUTOR_DIR);
                jc.setExecutorDir(executorDir);
                JobMessage next = new JobMessage("next")
                        .put("preCommands", config.getPreCommands(jc))
                        .put("mainCommands", config.getMainCommands(jc))
                        .put("postCommands", config.getPostCommands(jc));
                next.setChunkId(jc.getId());
                return next;
            case "upload":
                logger.info("upload: {}", jm);
                handleUpload(jm.getBytes(), jm.getChunkId());
                JobMessage upload = new JobMessage("upload");
                upload.setChunkId(jm.getChunkId());
                return upload;
            default:
                logger.warn("unknown request method: {}", method);
                return null;
        }
    }

    private byte[] getDownload() {
        try {
            InputStream is = new FileInputStream(ZIP_FILE);
            return FileUtils.toBytes(is);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handleUpload(byte[] bytes, String chunkId) {
        JobChunk<T> jc;
        synchronized (chunks) {
            jc = chunks.get(chunkId);
        }        
        String chunkBasePath = basePath + File.separator + jc.getExecutorId() + File.separator + chunkId;
        File upload = new File(chunkBasePath);
        File zipFile = new File(chunkBasePath + ".zip");
        if (bytes != null) {
            FileUtils.writeToFile(zipFile, bytes);
            JobUtils.unzip(zipFile, upload);
        }
        T value = config.handleUpload(jc, upload);
        CompletableFuture<T> future = jc.getFuture();
        future.complete(value);
        logger.debug("completed: {}", chunkId);
    }

    protected void dumpLog(JobMessage jm) {
        logger.debug("\n>>>>>>>>>>>>>>>>>>>>> {}\n{}<<<<<<<<<<<<<<<<<<<< {}", jm, jm.get("log"), jm);
    }

}
