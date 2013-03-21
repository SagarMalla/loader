package com.open.perf.sample.run;

import com.open.perf.domain.Group;
import com.open.perf.domain.GroupFunction;
import com.open.perf.domain.Loader;
import com.open.perf.sample.function.DummyFunction;

import java.util.UUID;

public class GroupStatsInstanceTestClass {
    public static void main (String[]args) throws Exception {
        new Loader("Run").
                setJobId(UUID.randomUUID().toString()).
                addGroup(
                        new Group("G1").
                                setGroupStartDelay(0).
                                setRepeats(Integer.parseInt(args[0])).
                                setThreads(Integer.parseInt(args[1])).
                                setThroughput(Integer.parseInt(args[2])).
                                addFunction(new GroupFunction("RandomDelay").
                                        setFunctionClass(DummyFunction.class.getCanonicalName()))).
                start();

    }
}