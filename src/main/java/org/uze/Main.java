package org.uze;

import com.google.common.base.Stopwatch;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import groovy.lang.GroovyClassLoader;
import groovy.transform.CompileStatic;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.concurrent.TimeUnit;

/**
 * Run with
 * -XX:ReservedCodeCacheSize=12m
 * -XX:InitialCodeCacheSize=2m
 * -XX:CompileThreshold=1000
 * -XX:+PrintCodeCache
 * -XX:+UseCodeCacheFlushing
 */
public final class Main {

    private static final String SCRIPT = "long result = count;\n" +
            "for (long i = 0; i < count; i++) {\n" +
            "    result += java.util.concurrent.ThreadLocalRandom.current().nextLong();\n" +
            "}\n" +
            "return result;";

    public static void main(String[] args) throws Exception {
        new Main().run();
    }

    private GroovyClassLoader createClassLoader() {
        final CompilerConfiguration cc = new CompilerConfiguration();
        cc.setScriptBaseClass(MyScript.class.getName());
        cc.addCompilationCustomizers(
                new ASTTransformationCustomizer(CompileStatic.class)
        );
        final GroovyClassLoader gcl = new GroovyClassLoader(MyScript.class.getClassLoader(), cc);
        gcl.setShouldRecompile(false);
        return gcl;
    }

    private void run() throws Exception {
        final int compilerThreshold = getCompilerThreshold();
        final MemoryPoolMXBean codeCache = getCodeCache();
        System.out.println("Elapsed (s)  Used Code Cache (Mb)  Max Code Cache (Mb)  Invocations/s");
        final Stopwatch sw = Stopwatch.createStarted();
        final Stopwatch scriptTimer = Stopwatch.createUnstarted();
        final Stopwatch time = Stopwatch.createStarted();
        long totalIterations = 0;
        long accumulator = 0;
        GroovyClassLoader gcl = null;
        while (!Thread.currentThread().isInterrupted()) {
            if (gcl == null) {
                gcl = createClassLoader();
            }
            final MyScript script = (MyScript) gcl.parseClass(SCRIPT).newInstance();
            scriptTimer.start();
            accumulator += call(script, compilerThreshold);
            totalIterations += compilerThreshold;
            scriptTimer.stop();
            if (sw.elapsed(TimeUnit.SECONDS) > 5) {
                sw.reset();
                final double rate = totalIterations * 1_000.0 / scriptTimer.elapsed(TimeUnit.MILLISECONDS);
                System.out.println(String.format("+%d    %.2f    %.2f    %.2f",
                        time.elapsed(TimeUnit.SECONDS),
                        codeCache.getUsage().getUsed() / (1024.0 * 1024.0),
                        codeCache.getUsage().getMax() / (1024.0 * 1024.0),
                        rate
                ));
                scriptTimer.reset();
                totalIterations = 0;
                // comment next line to eventually face disabled compilation
                gcl = null;
                sw.start();
            }
        }
        System.out.println(accumulator);
    }

    private int getCompilerThreshold() {
        int threshold = 10_000;
        try {
            final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            final HotSpotDiagnosticMXBean diagnosticMXBean = ManagementFactory.newPlatformMXBeanProxy(
                    mBeanServer,
                    "com.sun.management:type=HotSpotDiagnostic",
                    HotSpotDiagnosticMXBean.class
            );
            final VMOption compileThresholdOpt = diagnosticMXBean.getVMOption("CompileThreshold");
            System.out.println(compileThresholdOpt);
            threshold = Integer.parseInt(compileThresholdOpt.getValue());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return threshold;
    }

    private long call(MyScript script, int count) {
        script.setCount(10_000);
        long results = 0;
        for (int i = 0; i < count; i++) {
            final long result = (long) script.run();
            results += result;
        }
        return results;
    }

    private MemoryPoolMXBean getCodeCache() {
        for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
            if (bean.getName().equals("Code Cache")) {
                return bean;
            }
        }
        throw new IllegalStateException("Code cache Mbean not found!");
    }

}
