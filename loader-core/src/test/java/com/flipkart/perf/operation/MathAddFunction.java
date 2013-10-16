package com.flipkart.perf.operation;

import com.flipkart.perf.common.util.Clock;
import com.flipkart.perf.core.FunctionContext;
import com.flipkart.perf.function.FunctionParameter;
import com.flipkart.perf.function.PerformanceFunction;
import com.flipkart.perf.util.TimerContext;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Can be used to introduce Delay in the Group
 */
public class MathAddFunction extends PerformanceFunction {
    public static final String IP_NUM_JSON_LIST = "Number Json List";

    @Override
    public void init(FunctionContext context) {
        logger.info("Init");
    }

    @Override
    public void execute(FunctionContext context) throws Exception {
        List numList = context.getParameterAsList(IP_NUM_JSON_LIST);
        double  sum = 0.0;
        TimerContext c = context.startTimer("sleepTimer");
        Clock.sleep(1000);
        for(Object num : numList) {
            sum += Double.parseDouble(num.toString());
            context.incrementCounter("numbers");
        }
        context.updateHistogram("sum", sum);
        c.stop();
        logger.info("Sum of "+numList +" : "+sum);
    }

    @Override
    public LinkedHashMap<String, FunctionParameter> inputParameters() {
        LinkedHashMap<String, FunctionParameter> parameters = new LinkedHashMap<String, FunctionParameter>();
        parameters.put(IP_NUM_JSON_LIST, new FunctionParameter().
                setName(IP_NUM_JSON_LIST).
                setDefaultValue(1).
                setMandatory(true).
                setDescription("List of numbers as json list"));
        return parameters;
    }
}