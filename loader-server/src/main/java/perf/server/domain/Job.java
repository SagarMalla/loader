package perf.server.domain;

import java.util.*;

public class Job {

    public static enum JOB_STATUS {
        QUEUED, RUNNING, PAUSED, COMPLETED, KILLED, FAILED_TO_START;
    }
    private String jobId;
    private String runName;
    private Date startTime, endTime;
    private JOB_STATUS jobStatus;
    private Map<String,JOB_STATUS> agentsJobStatus;
    private Set<String> monitoringAgents;

    public Job() {
        this.jobStatus = JOB_STATUS.QUEUED;
        this.monitoringAgents = new HashSet<String>();
        this.agentsJobStatus = new HashMap<String, JOB_STATUS>();
    }

    public String getJobId() {
        return jobId;
    }

    public Job setJobId(String jobId) {
        this.jobId = jobId;
        return this;
    }

    public String getRunName() {
        return runName;
    }

    public Job setRunName(String runName) {
        this.runName = runName;
        return this;
    }

    public Date getStartTime() {
        return startTime;
    }

    public Job setStartTime(Date startTime) {
        this.startTime = startTime;
        return this;
    }

    public Date getEndTime() {
        return endTime;
    }

    public Job setEndTime(Date endTime) {
        this.endTime = endTime;
        return this;
    }

    public JOB_STATUS getJobStatus() {
        return jobStatus;
    }

    public Job setJobStatus(JOB_STATUS jobStatus) {
        this.jobStatus = jobStatus;
        return this;
    }

    public Set<String> getMonitoringAgents() {
        return monitoringAgents;
    }

    public void setMonitoringAgents(Set<String> monitoringAgents) {
        this.monitoringAgents = monitoringAgents;
    }

    public Job addMonitoringAgent(String agentIp) {
        monitoringAgents.add(agentIp);
        return this;
    }

    public Map<String, JOB_STATUS> getAgentsJobStatus() {
        return agentsJobStatus;
    }

    public Job setAgentsJobStatus(Map<String, JOB_STATUS> agentsJobStatus) {
        this.agentsJobStatus = agentsJobStatus;
        return this;
    }

    public Job jobRunningInAgent(String agentIp) {
        this.jobStatus = JOB_STATUS.RUNNING;
        this.agentsJobStatus.put(agentIp, JOB_STATUS.RUNNING);
        return this;
    }

    public Job jobCompletedInAgent(String agentIp) {
        JOB_STATUS jobStatusInAgent = this.agentsJobStatus.get(agentIp);
        if(jobStatusInAgent != JOB_STATUS.KILLED)
            this.agentsJobStatus.put(agentIp, JOB_STATUS.COMPLETED);

        if(!this.agentsJobStatus.containsValue(JOB_STATUS.RUNNING) &&
                !this.agentsJobStatus.containsValue(JOB_STATUS.PAUSED))
            this.jobStatus = JOB_STATUS.COMPLETED;

        return this;
    }

    public Job jobPausedInAgent(String agentIp) {
        this.agentsJobStatus.put(agentIp, JOB_STATUS.PAUSED);
        return this;
    }

    public Job jobKilledInAgent(String agentIp) {
        JOB_STATUS jobStatusInAgent = this.agentsJobStatus.get(agentIp);
        if(jobStatusInAgent != JOB_STATUS.COMPLETED)
            this.agentsJobStatus.put(agentIp, JOB_STATUS.KILLED);

        if(!this.agentsJobStatus.containsValue(JOB_STATUS.RUNNING) &&
                !this.agentsJobStatus.containsValue(JOB_STATUS.PAUSED))
            this.jobStatus = JOB_STATUS.COMPLETED;
        return this;
    }

    public boolean completed() {
        return this.getJobStatus().equals(Job.JOB_STATUS.COMPLETED) ||
                        this.getJobStatus().equals(Job.JOB_STATUS.KILLED);
    }
}