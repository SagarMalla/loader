package com.flipkart.perf.agent.resource;

import com.codahale.metrics.annotation.Timed;
import com.flipkart.perf.agent.cache.LibCache;
import com.flipkart.perf.agent.config.JobFSConfig;
import com.flipkart.perf.agent.config.JobProcessorConfig;
import com.flipkart.perf.agent.daemon.JobProcessorThread;
import com.flipkart.perf.agent.job.AgentJob;
import com.flipkart.perf.agent.util.AgentJobHelper;
import com.flipkart.perf.common.util.FileHelper;
import com.sun.jersey.multipart.FormDataParam;
import io.dropwizard.jersey.params.IntParam;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Receive Requests about jobs
 */
@Path("/loader-agent/jobs")

public class JobResource {
    private JobProcessorThread jobProcessorThread = JobProcessorThread.instance();
    private JobProcessorConfig jobProcessorConfig;
    private final JobFSConfig jobFSconfig;

    public JobResource(JobProcessorConfig jobProcessorConfig, JobFSConfig jobFSConfig) {
        this.jobProcessorConfig = jobProcessorConfig;
        this.jobFSconfig = jobFSConfig;
    }

    @POST
    @Timed
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public String queueJob(@FormDataParam("jobId") String jobId,
                           @FormDataParam("jobJson") InputStream jobJson,
                           @FormDataParam("classList") String classListStr) throws IOException {

        List<String> classList = Arrays.asList(classListStr.split("\n"));

        String jobClassPath = LibCache.getInstance().
                buildJobClassPath(classList);

        String jobCMD = this.jobProcessorConfig.getJobCLIFormat().
                replace("{classpath}", jobClassPath).
                replace("{jobJson}", ""+ FileHelper.persistStream(jobJson, "/tmp/" + System.currentTimeMillis())).
                replace("{jobId}", jobId);

        AgentJob agentJob = new AgentJob().
                setJobCmd(jobCMD).
                setJobId(jobId);

        jobProcessorThread.addJobRequest(agentJob);
        return agentJob.getJobId();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{jobId}")
    @GET
    @Timed
    public AgentJob getJob(@PathParam("jobId") String jobId) throws IOException, InterruptedException {
        return AgentJobHelper.jobExistsOrException(jobId);
    }

    @GET
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public Map getJobs(@QueryParam("status") @DefaultValue("") String jobStatus) {
        return jobProcessorThread.
                getJobs(jobStatus);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{jobId}/kill")
    @PUT
    @Timed
    public AgentJob kill(@PathParam("jobId") String jobId) throws IOException, InterruptedException, ExecutionException {
        AgentJob agentJob = AgentJobHelper.jobExistsOrException(jobId);
        return agentJob.kill();
    }

    @Produces(MediaType.TEXT_PLAIN)
    @Path("/{jobId}/log")
    @GET
    @Timed
    public InputStream log(@PathParam("jobId") String jobId,
                      @QueryParam("lines") @DefaultValue("100") IntParam lines,
                      @QueryParam("grep") @DefaultValue("") String grepExp)
            throws IOException, InterruptedException {
        AgentJobHelper.jobExistsOrException(jobId);
        String jobLogFile = jobFSconfig.getJobLogFile(jobId);
        if(new File(jobLogFile).exists()) {

            // Build Command
            StringBuilder cmdBuilder = new StringBuilder();
            if(lines.get().intValue() > 0) {
                cmdBuilder.append("tail -"+lines.get().intValue() + " " + jobLogFile);
            }

            if(!grepExp.trim().equals("")) {
                if(cmdBuilder.toString().equals("")) {
                    cmdBuilder.append(" grep "+grepExp + " " + jobLogFile);
                }
                else {
                    cmdBuilder.append(" | grep "+grepExp.replace(" ","\\ "));
                }
            }

            // Execute Command
            if(!cmdBuilder.equals("")) {
                cmdBuilder.append(" | sed 's/$/<br>/'");
                String[] cmd = {
                        "/bin/sh",
                        "-c",
                        cmdBuilder.toString()
                };

                Process process = Runtime.getRuntime().exec(cmd);
                process.waitFor();
                return process.getInputStream();
            }
        }
        return new ByteArrayInputStream("".getBytes());
    }
}