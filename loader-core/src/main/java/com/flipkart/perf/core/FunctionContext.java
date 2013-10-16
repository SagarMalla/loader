package com.flipkart.perf.core;

import com.flipkart.perf.common.util.*;
import com.flipkart.perf.config.FSConfig;
import com.flipkart.perf.common.jackson.ObjectMapperUtil;
import com.flipkart.perf.util.Counter;
import com.flipkart.perf.util.Histogram;
import com.flipkart.perf.util.Timer;
import com.flipkart.perf.util.TimerContext;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FunctionContext {

    public static enum FailureType {
        GENERAL, DATA_MISSING, FUNCTIONAL_FAILURE, WRONG_INPUT
    }

    private static ObjectMapper mapper = ObjectMapperUtil.instance();
    private Thread myThread;
    private Map<String, Object> functionParameters;
    private Map<String, Counter> counters;
    private Map<String, Timer> timers;
    private Map<String, Histogram> histograms;
    private Map<String, Object> passOnParameters; // Will be populated by User in function and would be passed further
    private boolean skipFurtherFunctions = false;
    private FailureType failureType;
    private String failureMessage;
    private boolean currentFunctionFailed;
    private long startTime = -1;
    private long time = -1;

    private static Map<String, String> inputFileResources;
    private static final Pattern variablePattern;

    static {
        inputFileResources = FSConfig.inputFileResources();
        variablePattern = Pattern.compile(".*\\$\\{(.+)\\}.*");
    }

    public FunctionContext(Map<String,Timer> functionTimers, Map<String,Counter> functionCounters, Map<String, Histogram> functionHistograms) {
        this.functionParameters = new HashMap<String, Object>();
        this.timers = functionTimers;
        this.counters = functionCounters;
        this.histograms = functionHistograms;
        this.passOnParameters = new HashMap<String, Object>();
    }

    public File getResourceAsFile(String resourceName) {
        File file = new File(FSConfig.getInputFilePath(resourceName));
        if(file.exists())
            return file;
        return null;
    }

    public InputStream getResourceAsInputStream(String resourceName) throws FileNotFoundException {
        File file = new File(FSConfig.getInputFilePath(resourceName));
        if(file.exists())
            return new FileInputStream(file);
        return null;
    }

    public Object getParameter(String parameterName) {
        Object value = passOnParameters.get(parameterName);
        if(value == null)
            value = functionParameters.get(parameterName);

        // Resolve variable parameters
        if(value instanceof String) {
            String valueString = value.toString();
            Matcher matcher = variablePattern.matcher(valueString);
            while(matcher.matches()) {
                String varName = matcher.group(1);
                Object replacementValue = inputFileResources.get(varName);
                if(replacementValue == null)
                    replacementValue = functionParameters.get(varName);

                if(replacementValue == null)
                    replacementValue = passOnParameters.get(varName);

                if(replacementValue == null)
                    replacementValue = "null";

                valueString = valueString.replace("${"+varName+"}", replacementValue.toString());
                matcher = variablePattern.matcher(valueString);
            }
            value = valueString;
        }

        return value;
    }

    public String getParameterAsString(String parameterName) {
        return getParameterAsString(parameterName, null);
    }

    public String getParameterAsString(String parameterName, String defaultValue) {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : value.toString();
    }

    public Integer getParameterAsInteger(String parameterName) {
        return getParameterAsInteger(parameterName, null);
    }

    public Integer getParameterAsInteger(String parameterName, Integer defaultValue) {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : Integer.parseInt(value.toString());
    }

    public Long getParameterAsLong(String parameterName) {
        return  getParameterAsLong(parameterName, null);
    }

    public Long getParameterAsLong(String parameterName, Long defaultValue) {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : Long.parseLong(value.toString());
    }

    public Float getParameterAsFloat(String parameterName) {
        return getParameterAsFloat(parameterName, null);
    }

    public Float getParameterAsFloat(String parameterName, Float defaultValue) {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : Float.parseFloat(value.toString());
    }

    public Double getParameterAsDouble(String parameterName) {
        return getParameterAsDouble(parameterName, null);
    }

    public Double getParameterAsDouble(String parameterName, Double defaultValue) {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : Double.parseDouble(value.toString());
    }

    public Boolean getParameterAsBoolean(String parameterName) {
        return getParameterAsBoolean(parameterName, false);
    }

    public Boolean getParameterAsBoolean(String parameterName, Boolean defaultValue) {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    public File getParameterAsFile(String parameterName) {
        Object value = getParameter(parameterName);
        return value == null ? null : new File(value.toString());
    }

    public InputStream getParameterAsInputStream(String parameterName) throws FileNotFoundException {
        Object value = getParameter(parameterName);
        return value == null ? null : new FileInputStream(value.toString());
    }

    public Map getParameterAsMap(String parameterName) throws IOException {
        return getParameterAsMap(parameterName, null);
    }

    public Map getParameterAsMap(String parameterName, Map defaultValue) throws IOException {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : mapper.readValue(value.toString().replace("'", "\""), Map.class);
    }

    public List getParameterAsList(String parameterName) throws IOException {
        return getParameterAsList(parameterName, null);
    }

    public List getParameterAsList(String parameterName, List defaultValue) throws IOException {
        Object value = getParameter(parameterName);
        return value == null ? defaultValue : mapper.readValue(value.toString(), List.class);
    }

    /**
     * Get all parameters
     * @return
     */
    public Map<String, Object> getParameters() {
        return getGroupedParameters(".*");
    }

    /**
     * this will group the parameter based in keys matching the regex, prepare a map and return
     * @param keyMatchingRegEx
     * @return
     */
    public Map<String, Object> getGroupedParameters(String keyMatchingRegEx) {
        Map<String, Object> allParams = new HashMap<String, Object>();
        allParams.putAll(functionParameters);
        allParams.putAll(passOnParameters);

        Map<String,Object> groupedMap = new HashMap<String, Object>();
        for(String key : allParams.keySet()) {
            if(Pattern.matches(keyMatchingRegEx, key))
                groupedMap.put(key, allParams.get(key));
        }
        return groupedMap;
    }

    /**
     * this will group the parameter based in keys matching the regex, prepare a map and return
     * In addition it would reduce the key to the matching group in the regex
     * @param keyMatchingRegEx
     * @param groupId
     * @return
     */
    public Map<String,Object> getGroupedParameters(String keyMatchingRegEx, int groupId) {
        Map<String, Object> allParams = new HashMap<String, Object>();
        allParams.putAll(functionParameters);
        allParams.putAll(passOnParameters);

        Map<String,Object> groupedMap = new HashMap<String, Object>();
        Pattern p = Pattern.compile(keyMatchingRegEx);
        for(String key : allParams.keySet()) {
            Matcher m = p.matcher(key);
            if(m.matches())
                groupedMap.put(m.group(groupId), allParams.get(key));
        }
        return groupedMap;
    }

    /**
     * Would Over ride any passed on variable from previous function execution in group
     * @param parameterName
     * @param value
     */
    public FunctionContext addParameter(String parameterName, Object value) {
        passOnParameters.put(parameterName, value);
        return this;
    }

    public TimerContext startTimer(String timerName) {
        Timer timer = this.timers.get(timerName);
        TimerContext context;
        if(timer == null)
            context = new TimerContext(null);
        else
            context = timer.startTimer();
        return context;
    }

    public FunctionContext updateHistogram(String histogramName, double value) {
        Histogram histogram = this.histograms.get(histogramName);
        if(histogram != null)
            histogram.addValue(value);
        return this;
    }

    public FunctionContext incrementCounter(String counterName) {
        Counter counter = this.counters.get(counterName);
        if(counter != null)
            counter.increment();
        return this;
    }

    public FunctionContext incrementCounter(String counterName, int by) {
        Counter counter = this.counters.get(counterName);
        if(counter != null)
            counter.increment(by);
        return this;
    }

    public FunctionContext decrementCounter(String counterName) {
        Counter counter = this.counters.get(counterName);
        if(counter != null)
            counter.decrement();
        return this;
    }

    public FunctionContext decrementCounter(String counterName, int by) {
        Counter counter = this.counters.get(counterName);
        if(counter != null)
            counter.decrement(by);
        return this;
    }

    public FunctionContext updateParameters(Map<String, Object> params) {
        this.functionParameters.putAll(params);
        return this;
    }

    public FunctionContext failed(FailureType failureType, String message) {
        this.currentFunctionFailed = true;
        this.failureMessage = message;
        this.failureType = failureType;
        this.skipFurtherFunctions();
        return this;
    }

    boolean isCurrentFunctionFailed() {
        return currentFunctionFailed;
    }

    public void skipFurtherFunctions() {
        this.skipFurtherFunctions = true;
    }

    boolean isSkipFurtherFunctions() {
        return skipFurtherFunctions;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public FunctionContext setMyThread(Thread myThread) {
        this.myThread = myThread;
        return this;
    }

    public FunctionContext async() {
        synchronized (myThread) {
            try {
                myThread.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
        return this;
    }

    public FunctionContext join() {
        synchronized (myThread) {
            myThread.notify();
        }
        return this;
    }

    public FunctionContext startMe() {
        this.startTime = Clock.nsTick();
        return this;
    }

    public FunctionContext endMe() {
        if(this.startTime == -1)
            throw new RuntimeException("User calling endMe without calling startMe");
        this.time = Clock.nsTick()- this.startTime;
        return this;
    }

    long getTimeNS() {
        return time;
    }

    public void reset() {
        this.functionParameters.clear();
        this.skipFurtherFunctions = false;
        this.passOnParameters.clear();
        this.currentFunctionFailed = false;
        this.failureMessage=null;
        this.failureType=null;
        this.startTime = -1;
        this.time = -1;
    }
}