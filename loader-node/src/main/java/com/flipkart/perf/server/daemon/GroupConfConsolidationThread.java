package com.flipkart.perf.server.daemon;

import com.flipkart.perf.common.constant.MathConstant;
import com.flipkart.perf.common.util.Clock;
import com.flipkart.perf.common.util.FileHelper;
import com.flipkart.perf.server.cache.JobsCache;
import com.flipkart.perf.server.config.JobFSConfig;
import com.flipkart.perf.server.util.ObjectMapperUtil;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Compute both agent and overall timer files.
 */
public class GroupConfConsolidationThread extends Thread {

    private static ObjectMapper objectMapper;
    private final JobFSConfig jobFSConfig;
    private boolean stop = false;
    private List<String> aliveJobs;

    private Map<String,Long> fileAlreadyReadLinesMap;       // This would be further improved once i implement small file approach for big timer files
    private Map<String,List> fileCachedContentMap;

    private static GroupConfConsolidationThread thread;
    private static Logger logger;
    private static final String FILE_EXTENSION;

    static class RealTimeGroupConf {
        private long time;
        private float threads;
        private float throughput;

        public long getTime() {
            return time;
        }

        public void setTime(long time) {
            this.time = time;
        }

        public float getThreads() {
            return threads;
        }

        public void setThreads(float threads) {
            this.threads = threads;
        }

        public float getThroughput() {
            return throughput;
        }

        public void setThroughput(float throughput) {
            this.throughput = throughput;
        }
    }

    static {
        objectMapper = ObjectMapperUtil.instance();
        logger = LoggerFactory.getLogger(GroupConfConsolidationThread.class);
        FILE_EXTENSION = "stats";
    }

    private GroupConfConsolidationThread(JobFSConfig jobFSConfig) {
        this.jobFSConfig = jobFSConfig;
        this.aliveJobs = new ArrayList<String>();
        this.fileAlreadyReadLinesMap = new ConcurrentHashMap<String, Long>();
        this.fileCachedContentMap = new ConcurrentHashMap<String, List>();
    }

    public static GroupConfConsolidationThread initialize(ScheduledExecutorService scheduledExecutorService, JobFSConfig jobFSConfig, int interval) {
        if(thread == null) {
        	synchronized(GroupConfConsolidationThread.class) {
        		thread = new GroupConfConsolidationThread(jobFSConfig);
        	}
            scheduledExecutorService.scheduleWithFixedDelay(thread,
                    1000,
                    interval,
                    TimeUnit.MILLISECONDS);

        }
        return thread;
    }

    public static GroupConfConsolidationThread instance() {
        return thread;
    }

    public void run() {
        synchronized (this.aliveJobs) {
            for(String jobId : this.aliveJobs) {
                try {
                    crunchRealTimeGroupConf(jobId);
                } catch (FileNotFoundException e) {
                    logger.error("Error While Crunching stats for job "+jobId,e);
                } catch (IOException e) {
                    logger.error("Error While Crunching stats for job "+jobId,e);
                } catch (ExecutionException e) {
                    logger.error("Error While Crunching stats for job "+jobId,e);
                }
            }
        }
    }

    private void crunchRealTimeGroupConf(String jobId) throws IOException, ExecutionException {
        List<File> jobFiles = FileHelper.pathFiles(this.jobFSConfig.getJobStatsPath(jobId), true);
        for(File jobFile : jobFiles) {
            if(jobFile.getAbsolutePath().contains("realTimeConf") && !jobFile.getAbsolutePath().contains("stats")) {
                crunchRealTimeGroupConfFile(jobId, jobFile);
            }
        }
    }

    synchronized public void crunchRealTimeGroupConfFile(String jobId, File jobFile) throws IOException, ExecutionException {
        BufferedReader br = FileHelper.bufferedReader(jobFile.getAbsolutePath());

        // Skip the number of lines that are already read from this file
        long alreadyReadLines = skipAlreadyReadLines(jobFile, br);

        // Bring new Content in cache
        List<String> cachedContent = readAndCacheNewContent(jobFile, br, alreadyReadLines);

        // Sort the collected content(sorted would happen mostly with respect to 1st field in file, which is time in ms.
        Collections.sort(cachedContent);

        // Iterate and compute
        if(canParseContentNow(jobId, cachedContent, isAgentStats(jobFile))) {

            BufferedWriter bw = FileHelper.bufferedWriter(jobFile.getAbsolutePath() + "." + FILE_EXTENSION, true);

            // Group in performance run had only one repeat
            if(cachedContent.size() == 0 && jobOver(jobId)) {
                String currentLine = cachedContent.remove(0);
                String[] tokens = currentLine.split(",");

                RealTimeGroupConf realTimeGroupConf = new RealTimeGroupConf();
                realTimeGroupConf.time = Long.parseLong(tokens[0]);
                realTimeGroupConf.threads = Float.parseFloat(tokens[1]);
                realTimeGroupConf.throughput = Float.parseFloat(tokens[2]);

                bw.write(objectMapper.writeValueAsString(realTimeGroupConf) + "\n");
                bw.flush();
                BufferedWriter bwLast = FileHelper.bufferedWriter(jobFile.getAbsolutePath()+"."+FILE_EXTENSION+".last", false);
                bwLast.write(objectMapper.writeValueAsString(realTimeGroupConf) + "\n");
                bwLast.flush();
                FileHelper.close(bwLast);
            }

            int lines = 0;
            int totalThreads = 0;
            float totalThroughput = 0.0f;
            long startTimeMS = 0l;
            while(cachedContent.size() > 0) {
                String currentLine = cachedContent.remove(0);
                StringTokenizer tokenizer = new StringTokenizer(currentLine, ",");
                long lineTimeMS = Long.parseLong(tokenizer.nextElement().toString());

                /**
                 * Recently Added code. Check the functionality
                 */
                if (shouldBreak(jobId, lineTimeMS, isAgentStats(jobFile))) {
                    cachedContent.add(0, currentLine);
                    break;
                }

                if(startTimeMS == 0l)
                    startTimeMS = lineTimeMS;

                totalThreads += Integer.parseInt(tokenizer.nextElement().toString());
                totalThroughput += Float.parseFloat(tokenizer.nextElement().toString());
                lines++;

                if((lineTimeMS - startTimeMS) > 10 * MathConstant.THOUSAND ||
                        (jobOver(jobId) && cachedContent.size() == 0)) {

                    int aliveAgents = JobsCache.getJob(jobId).aliveAgents().size();
                    if(aliveAgents == 0)
                        aliveAgents = 1;  // Fix it by caching last value of alive agents
                    float totalThreadsAcrossAgents = (totalThreads / lines) * aliveAgents;
                    float totalExpectedThroughputAcrossAgents = (totalThroughput / lines) * aliveAgents;

                    RealTimeGroupConf realTimeGroupConf = new RealTimeGroupConf();
                    realTimeGroupConf.time = lineTimeMS;
                    realTimeGroupConf.threads = totalThreadsAcrossAgents;
                    realTimeGroupConf.throughput = totalExpectedThroughputAcrossAgents;

                    bw.write(objectMapper.writeValueAsString(realTimeGroupConf) + "\n");
                    bw.flush();
                    BufferedWriter bwLast = FileHelper.bufferedWriter(jobFile.getAbsolutePath()+"."+FILE_EXTENSION+".last", false);
                    bwLast.write(objectMapper.writeValueAsString(realTimeGroupConf) + "\n");
                    bwLast.flush();
                    FileHelper.close(bwLast);

                    lines = 0;
                    totalThreads = 0;
                    totalThroughput = 0.0f;
                    startTimeMS = 0l;
                }
            }
            FileHelper.close(bw);
        }
        FileHelper.close(br);
    }

    // simple skip the number of lines already read in previous iteration
    private long skipAlreadyReadLines(File jobFile, BufferedReader br) throws IOException {
        long alreadyReadLines = 0;
        if(this.fileAlreadyReadLinesMap.containsKey(jobFile.getAbsolutePath()))
            alreadyReadLines = this.fileAlreadyReadLinesMap.get(jobFile.getAbsolutePath());

        for(long i=0; i< alreadyReadLines; i++)
            br.readLine(); // Skipping already read lines
        return alreadyReadLines;
    }

    // read all new content and cache it
    private List<String> readAndCacheNewContent(File jobFile, BufferedReader br, long alreadyReadLines) throws IOException {
        // Populate remaining content in a list
        List<String> cachedContent = this.fileCachedContentMap.get(jobFile.getAbsolutePath());
        if(cachedContent == null) {
            cachedContent = new LinkedList<String>();
            this.fileCachedContentMap.put(jobFile.getAbsolutePath(), cachedContent);
        }
        String line = null;
        while((line = br.readLine()) != null) {
            cachedContent.add(line);
            alreadyReadLines++;
        }
        this.fileAlreadyReadLinesMap.put(jobFile.getAbsolutePath(), alreadyReadLines);
        return cachedContent;
    }


    private boolean isAgentStats(File jobFile) {
        return !jobFile.getAbsolutePath().contains("combined");
    }

    /**
     * If stepped on data which is in last 60 seconds.
     * @param jobId
     * @param lineTimeMS
     * @return
     */
    private boolean shouldBreak(String jobId, double lineTimeMS, boolean isAgentStats) {
        return ((Clock.milliTick() - lineTimeMS) < (isAgentStats ? 20 : 60) * MathConstant.THOUSAND) && !jobOver(jobId);
    }

    private boolean canParseContentNow(String jobId, List<String> cachedContent, boolean isAgentStats) {
        if(cachedContent.size() == 0)
            return false;
        String firstLine = cachedContent.get(0);
        StringTokenizer firstLineTokenizer = new StringTokenizer(firstLine, ",");
        long firstLineTimeMS = Long.parseLong(firstLineTokenizer.nextElement().toString());
        long currentTimeMS = Clock.milliTick();
        logger.info("First Line in Cached Content is  :"+(currentTimeMS - firstLineTimeMS)+"ms old");

        String lastLine = cachedContent.get(cachedContent.size()-1);
        StringTokenizer lastLineTokenizer = new StringTokenizer(lastLine, ",");
        long lastLineTimeMS = Long.parseLong(lastLineTokenizer.nextElement().toString());
        logger.info("Cached Content has data for  :"+(lastLineTimeMS - firstLineTimeMS)+"ms time");

        return ((currentTimeMS - firstLineTimeMS) > ((isAgentStats ? 20 : 60) + 30) * MathConstant.THOUSAND) || jobOver(jobId); // Crunch if data is older than 60 + 30 seconds
    }

    public void addJob(String jobId) {
        synchronized (this.aliveJobs) {
            this.aliveJobs.add(jobId);
        }
    }

    public void removeJob(String jobId) throws IOException, ExecutionException {
        synchronized (this.aliveJobs) {
            this.aliveJobs.remove(jobId);
            crunchRealTimeGroupConf(jobId);
        }
    }

    private boolean jobOver(String jobId) {
        return !this.aliveJobs.contains(jobId);
    }
}