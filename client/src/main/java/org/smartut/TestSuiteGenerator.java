/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * Copyright (C) 2021- SmartUt contributors
 *
 * SmartUt is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * SmartUt is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with SmartUt. If not, see <http://www.gnu.org/licenses/>.
 */
package org.smartut;

import org.smartut.Properties.AssertionStrategy;
import org.smartut.Properties.Criterion;
import org.smartut.Properties.TestFactory;
import org.smartut.classpath.ClassPathHacker;
import org.smartut.classpath.ClassPathHandler;
import org.smartut.contracts.ContractChecker;
import org.smartut.contracts.FailingTestSet;
import org.smartut.coverage.CoverageCriteriaAnalyzer;
import org.smartut.coverage.FitnessFunctions;
import org.smartut.coverage.TestFitnessFactory;
import org.smartut.coverage.dataflow.DefUseCoverageSuiteFitness;
import org.smartut.ga.metaheuristics.GeneticAlgorithm;
import org.smartut.ga.stoppingconditions.StoppingCondition;
import org.smartut.junit.JUnitAnalyzer;
import org.smartut.junit.writer.TestSuiteWriter;
import org.smartut.result.TestGenerationResult;
import org.smartut.result.TestGenerationResultBuilder;
import org.smartut.rmi.ClientServices;
import org.smartut.rmi.service.ClientState;
import org.smartut.runtime.LoopCounter;
import org.smartut.runtime.sandbox.PermissionStatistics;
import org.smartut.seeding.ObjectPool;
import org.smartut.seeding.ObjectPoolManager;
import org.smartut.setup.DependencyAnalysis;
import org.smartut.setup.ExceptionMapGenerator;
import org.smartut.setup.TestCluster;
import org.smartut.statistics.RuntimeVariable;
import org.smartut.statistics.StatisticsSender;
import org.smartut.strategy.TestGenerationStrategy;
import org.smartut.symbolic.DSEStats;
import org.smartut.testcase.*;
import org.smartut.testcase.execution.*;
import org.smartut.testcase.execution.reset.ClassReInitializer;
import org.smartut.testcase.statements.MethodStatement;
import org.smartut.testcase.statements.Statement;
import org.smartut.testcase.statements.StringPrimitiveStatement;
import org.smartut.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.smartut.testcase.variable.VariableReference;
import org.smartut.testsuite.*;
import org.smartut.utils.ArrayUtil;
import org.smartut.utils.CallUtil;
import org.smartut.utils.LoggingUtils;
import org.smartut.utils.generic.GenericMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.*;

/**
 * Main entry point. Does all the static analysis, invokes a test generation
 * strategy, and then applies postprocessing.
 * 
 * @author Gordon Fraser
 */
public class TestSuiteGenerator {

	private static final String FOR_NAME = "forName";
	private static final Logger logger = LoggerFactory.getLogger(TestSuiteGenerator.class);


	private void initializeTargetClass() throws Throwable {
		logger.warn("initializeTargetClass start！");
		String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();

		// Generate inheritance tree and call graph *before* loading the CUT
		// as these are required for instrumentation for context-sensitive
		// criteria (e.g. ibranch)
		DependencyAnalysis.initInheritanceTree(Arrays.asList(cp.split(File.pathSeparator)));
		DependencyAnalysis.initCallGraph(Properties.TARGET_CLASS);

		// Here is where the <clinit> code should be invoked for the first time
		DefaultTestCase test = buildLoadTargetClassTestCase(Properties.TARGET_CLASS);
		ExecutionResult execResult = TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE);

		if (hasThrownInitializerError(execResult)) {
			// create single test suite with Class.forName()
			writeJUnitTestSuiteForFailedInitialization();
			ExceptionInInitializerError ex = getInitializerError(execResult);
			throw ex;
		} else if (!execResult.getAllThrownExceptions().isEmpty()) {
			// some other exception has been thrown during initialization
			Throwable t = execResult.getAllThrownExceptions().iterator().next();
			throw t;
		}
		if (Properties.CODE_ANALYSIS_PLUGINS != null){
			for (String codeAnalysisPlugin : Properties.CODE_ANALYSIS_PLUGINS) {
				CallUtil.call(codeAnalysisPlugin, "analyze");
			}
		}
		// Analysis has to happen *after* the CUT is loaded since it will cause
		// several other classes to be loaded (including the CUT), but we require
		// the CUT to be loaded first
		DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Arrays.asList(cp.split(File.pathSeparator)));
		LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Finished analyzing classpath");
		logger.warn("initializeTargetClass finish！");
	}

	/**
	 * Generate a test suite for the target class
	 * 
	 * @return a {@link java.lang.String} object.
	 */
	public TestGenerationResult generateTestSuite() {

		LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Analyzing classpath: ");

		ClientServices.getInstance().getClientNode().changeState(ClientState.INITIALIZATION);

		// Deactivate loop counter to make sure classes initialize properly
		LoopCounter.getInstance().setActive(false);
		ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);

		TestCaseExecutor.initExecutor();
		try {
			initializeTargetClass();
		} catch (Throwable e) {

			// If the bytecode for a method exceeds 64K, Java will complain
			// Very often this is due to mutation instrumentation, so this dirty
			// hack adds a fallback mode without mutation.
			// This currently breaks statistics and assertions, so we have to also set these properties
			boolean error = true;

			String message = e.getMessage();
			if (message != null && (message.contains("Method code too large") || message.contains("Class file too large"))) {
				LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Instrumentation exceeds Java's 64K limit per method in target class");
				Properties.Criterion[] newCriteria = Arrays.stream(Properties.CRITERION).filter(t -> !t.equals(Properties.Criterion.STRONGMUTATION) && !t.equals(Properties.Criterion.WEAKMUTATION) && !t.equals(Properties.Criterion.MUTATION)).toArray(Properties.Criterion[]::new);
				if(newCriteria.length < Properties.CRITERION.length) {
					TestGenerationContext.getInstance().resetContext();
					LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                            + "Attempting re-instrumentation without mutation");
					Properties.CRITERION = newCriteria;
					if(Properties.NEW_STATISTICS) {
						LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                                + "Deactivating SmartUt statistics because of instrumentation problem");
						Properties.NEW_STATISTICS = false;
					}

					try {
						initializeTargetClass();
						error = false;
					} catch(Throwable t) {
						// No-op, error handled below
					}
					if(Properties.ASSERTIONS && Properties.ASSERTION_STRATEGY == AssertionStrategy.MUTATION) {
						LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                                + "Deactivating assertion minimization because mutation instrumentation does not work");
						Properties.ASSERTION_STRATEGY = AssertionStrategy.ALL;
					}
				}
			}

		    if(error) {
				LoggingUtils.getSmartUtLogger().error("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Error while initializing target class: "
						+ (e.getMessage() != null ? e.getMessage() : e.toString()));
				logger.error("Problem for " + Properties.TARGET_CLASS + ". Full stack:", e);
				return TestGenerationResultBuilder.buildErrorResult(e.getMessage() != null ? e.getMessage() : e.toString());
			}

		} finally {
			if (Properties.RESET_STATIC_FIELDS) {
				configureClassReInitializer();

			}
			// Once class loading is complete we can start checking loops
			// without risking to interfere with class initialisation
			LoopCounter.getInstance().setActive(true);
		}

		/*
		 * Initialises the object pool with objects carved from SELECTED_JUNIT
		 * classes
		 */
		// TODO: Do parts of this need to be wrapped into sandbox statements?
		ObjectPoolManager.getInstance();

		LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Generating tests for class "
                + Properties.TARGET_CLASS);
		TestSuiteGeneratorHelper.printTestCriterion();

		if (!Properties.hasTargetClassBeenLoaded()) {
			// initialization failed, then build error message
			return TestGenerationResultBuilder.buildErrorResult("Could not load target class");
		}

		TestSuiteChromosome testCases = generateTests();

		// As post process phases such as minimisation, coverage analysis, etc., may call getFitness()
		// of each fitness function, which may try to update the Archive, in here we explicitly disable
		// Archive to avoid any problem and at the same time to improve the performance of post process
		// phases (as no CPU cycles would be wasted updating the Archive).
		Properties.TEST_ARCHIVE = false;

		TestGenerationResult result = null;
		if (ClientProcess.DEFAULT_CLIENT_NAME.equals(ClientProcess.getIdentifier())) {
			postProcessTests(testCases);
			ClientServices.getInstance().getClientNode().publishPermissionStatistics();
			PermissionStatistics.getInstance().printStatistics(LoggingUtils.getSmartUtLogger());

			// progressMonitor.setCurrentPhase("Writing JUnit test cases");
			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Writing tests to file");
			result = writeJUnitTestsAndCreateResult(testCases);
			writeJUnitFailingTests();
		}
		TestCaseExecutor.pullDown();
		/*
		 * TODO: when we will have several processes running in parallel, we ll
		 * need to handle the gathering of the statistics.
		 */
		ClientServices.getInstance().getClientNode().changeState(ClientState.WRITING_STATISTICS);

		LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Done!");
		LoggingUtils.getSmartUtLogger().info("");

		return result != null ? result : TestGenerationResultBuilder.buildSuccessResult();
	}

	/**
	 * Returns true iif the test case execution has thrown an instance of ExceptionInInitializerError
	 * 
	 * @param execResult of the test case execution
	 * @return true if the test case has thrown an ExceptionInInitializerError
	 */
	private static boolean hasThrownInitializerError(ExecutionResult execResult) {
		for (Throwable t : execResult.getAllThrownExceptions()) {
			if (t instanceof ExceptionInInitializerError) {
				return true;
			}
		}
		return false;
	}
	
	
	/**
	 * Returns the initialized  error from the test case execution
	 * 
	 * @param execResult of the test case execution
	 * @return null if there were no thrown instances of ExceptionInInitializerError
	 */
	private static ExceptionInInitializerError getInitializerError(ExecutionResult execResult) {
		for (Throwable t : execResult.getAllThrownExceptions()) {
			if (t instanceof ExceptionInInitializerError) {
				ExceptionInInitializerError exceptionInInitializerError = (ExceptionInInitializerError)t;
				return exceptionInInitializerError;
			}
		}
		return null;
	}

	/**
	 * Reports the initialized classes during class initialization to the
	 * ClassReInitializater and configures the ClassReInitializer accordingly
	 */
	private void configureClassReInitializer() {
		// add loaded classes during building of dependency graph
		ExecutionTrace execTrace = ExecutionTracer.getExecutionTracer().getTrace();
		final List<String> initializedClasses = execTrace.getInitializedClasses();
		ClassReInitializer.getInstance().addInitializedClasses(initializedClasses);
		// set the behaviour of the ClassReInitializer
		final boolean reset_all_classes = Properties.RESET_ALL_CLASSES_DURING_TEST_GENERATION;
		ClassReInitializer.getInstance().setReInitializeAllClasses(reset_all_classes);
	}

	private static void writeJUnitTestSuiteForFailedInitialization() throws SmartUtError {
		TestSuiteChromosome suite = new TestSuiteChromosome();
		DefaultTestCase test = buildLoadTargetClassTestCase(Properties.TARGET_CLASS);
		suite.addTest(test);
		writeJUnitTestsAndCreateResult(suite);
	}

	/**
	 * Creates a single Test Case that only loads the target class. 
	 * <code>
	 * Thread currentThread = Thread.currentThread();
	 * ClassLoader classLoader = currentThread.getClassLoader();
	 * classLoader.load(className);
	 * </code>
	 * @param className the class to be loaded
	 * @return
	 * @throws SmartUtError if a reflection error happens while creating the test case
	 */
	private static DefaultTestCase buildLoadTargetClassTestCase(String className) throws SmartUtError {
		DefaultTestCase test = new DefaultTestCase();

		StringPrimitiveStatement stmt0 = new StringPrimitiveStatement(test, className);
		VariableReference string0 = test.addStatement(stmt0);
		try {
			Method currentThreadMethod = Thread.class.getMethod("currentThread");
			Statement currentThreadStmt = new MethodStatement(test,
					new GenericMethod(currentThreadMethod, currentThreadMethod.getDeclaringClass()), null,
					Collections.emptyList());
			VariableReference currentThreadVar = test.addStatement(currentThreadStmt);

			Method getContextClassLoaderMethod = Thread.class.getMethod("getContextClassLoader");
			Statement getContextClassLoaderStmt = new MethodStatement(test,
					new GenericMethod(getContextClassLoaderMethod, getContextClassLoaderMethod.getDeclaringClass()),
					currentThreadVar, Collections.emptyList());
			VariableReference contextClassLoaderVar = test.addStatement(getContextClassLoaderStmt);

//			Method loadClassMethod = ClassLoader.class.getMethod("loadClass", String.class);
//			Statement loadClassStmt = new MethodStatement(test,
//					new GenericMethod(loadClassMethod, loadClassMethod.getDeclaringClass()), contextClassLoaderVar,
//					Collections.singletonList(string0));
//			test.addStatement(loadClassStmt);

			BooleanPrimitiveStatement stmt1 = new BooleanPrimitiveStatement(test, true);
			VariableReference boolean0 = test.addStatement(stmt1);
			
			Method forNameMethod = Class.class.getMethod("forName",String.class, boolean.class, ClassLoader.class);
			Statement forNameStmt = new MethodStatement(test,
					new GenericMethod(forNameMethod, forNameMethod.getDeclaringClass()), null,
					Arrays.<VariableReference>asList(string0, boolean0, contextClassLoaderVar));
			test.addStatement(forNameStmt);

			return test;
		} catch (NoSuchMethodException | SecurityException e) {
			throw new SmartUtError("Unexpected exception while creating Class Initializer Test Case");
		}
	}

	/**
	 * Apply any readability optimizations and other techniques that should use
	 * or modify the generated tests
	 * 
	 * @param testSuite
	 */
	protected void postProcessTests(TestSuiteChromosome testSuite) {

		logger.warn("start postProcessTests");
		// last check for update mock methods, we have done this in TestCodeVisitor before, however, before minimize &
		// assertion generation will be better. So move st.doesNeedToUpdateInputs() in TestCodeVisitor here
		for(TestChromosome testChromosome : testSuite.getTestChromosomes()){
			testChromosome.mockChange();
		}

		// should refresh last run result and re-calculate covered goals
		List<TestFitnessFunction> goals = new ArrayList<>();
		List<TestFitnessFactory<? extends TestFitnessFunction>> testFitnessFactories = getFitnessFactories();
		for (TestFitnessFactory<?> ff : testFitnessFactories) {
			goals.addAll(ff.getCoverageGoals());
		}
		for (TestChromosome test : testSuite.getTestChromosomes()) {
			test.setChanged(true);
			test.clearCachedResults();
			test.getTestCase().clearCoveredGoals();
			for (TestFitnessFunction goal : goals) {
				goal.isCovered(test);
			}
		}

		// If overall time is short, the search might not have had enough time
		// to come up with a suite without timeouts. However, they will slow
		// down
		// the rest of the process, and may lead to invalid tests
		testSuite.getTestChromosomes()
				.removeIf(t -> t.getLastExecutionResult() != null && (t.getLastExecutionResult().hasTimeout() ||
																	  t.getLastExecutionResult().hasTestException()));

		logger.warn("after last mock change");
		if (Properties.CTG_SEEDS_FILE_OUT != null) {
			TestSuiteSerialization.saveTests(testSuite, new File(Properties.CTG_SEEDS_FILE_OUT));
		} else if (Properties.TEST_FACTORY == TestFactory.SERIALIZATION) {
			TestSuiteSerialization.saveTests(testSuite,
					new File(Properties.SEED_DIR + File.separator + Properties.TARGET_CLASS));
		}

		/*
		 * Remove covered goals that are not part of the minimization targets,
		 * as they might screw up coverage analysis when a minimization timeout
		 * occurs. This may happen e.g. when MutationSuiteFitness calls
		 * BranchCoverageSuiteFitness which adds branch goals.
		 */
		// TODO: This creates an inconsistency between
		// suite.getCoveredGoals().size() and suite.getNumCoveredGoals()
		// but it is not clear how to update numcoveredgoals
		for (TestFitnessFunction f : testSuite.getCoveredGoals()) {
			if (!goals.contains(f)) {
				testSuite.removeCoveredGoal(f);
			}
		}

		logger.warn("postProcessTests testSuite before inline, size is {}", testSuite.size());
		if (Properties.INLINE) {
			ClientServices.getInstance().getClientNode().changeState(ClientState.INLINING);
			ConstantInliner inliner = new ConstantInliner(true);
			// progressMonitor.setCurrentPhase("Inlining constants");
			inliner.inline(testSuite);
		}
		logger.warn("postProcessTests testSuite after inline, size is {}", testSuite.size());

		logger.warn("postProcessTests testSuite size before minimize: {}", testSuite.getTestChromosomes().size());

		/**
		 *  minimize optimize：
		 *  1. case remove： based on covered goals，filter case，delete case without mut.
		 *  2. pre-minimize: for every case，delete useless privateAccess.setVariable statement.
		 *  3. minimize per case: for every case，delete statement backward，
		 *  run case after every statement delete for fitness guarantee.
		 */
		if (Properties.MINIMIZE) {
			ClientServices.getInstance().getClientNode().changeState(ClientState.MINIMIZATION);

			// 1. whole test minimize
			TestSuiteMinimizer minimizer = new TestSuiteMinimizer(testFitnessFactories);
			logger.warn("Start remove redundant test suite");
			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Minimizing test suite");
			// remove redundant and without mut cases
			minimizer.minimize(testSuite, false);
			logger.warn("Remove redundant test suite DONE");

			// 2. pre minimize
			double before = testSuite.getFitness();
			PreTestSuiteMiniMizer preMinimizer = new PreTestSuiteMiniMizer(testFitnessFactories);
			logger.warn("Start pre minimizing test suite");
			preMinimizer.minimize(testSuite);
			logger.warn("Pre minimize test suite DONE");

			// 3. gracefully delete
			logger.warn("Start gracefully delete test case");
			minimizer.minimizeByDeleteStatementPerTest(testSuite);
			logger.warn("gracefully delete DONE");

			double after = testSuite.getFitness();
			if (after > before + 0.01d) { // assume minimization
				throw new Error("SmartUnit bug: minimization lead fitness from " + before + " to " + after);
			}
		} else {
			if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
				LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping minimization because not enough time is left");
			}

			ClientServices.track(RuntimeVariable.Result_Size, testSuite.size());
			ClientServices.track(RuntimeVariable.Minimized_Size, testSuite.size());
			ClientServices.track(RuntimeVariable.Result_Length, testSuite.totalLengthOfTestCases());
			ClientServices.track(RuntimeVariable.Minimized_Length, testSuite.totalLengthOfTestCases());
		}

		if (Properties.COVERAGE) {
			ClientServices.getInstance().getClientNode().changeState(ClientState.COVERAGE_ANALYSIS);
			CoverageCriteriaAnalyzer.analyzeCoverage(testSuite);
		}

		//after minimize, save the tests as seeds
		logger.warn("postProcessTests testSuite size after minimize: {}", testSuite.getTestChromosomes().size());

		double coverage = testSuite.getCoverage();

		if (ArrayUtil.contains(Properties.CRITERION, Criterion.MUTATION)
				|| ArrayUtil.contains(Properties.CRITERION, Criterion.STRONGMUTATION)) {
			// SearchStatistics.getInstance().mutationScore(coverage);
		}

		StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
		LoggingUtils.getSmartUtLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier() + "Generated " + testSuite.size()
                + " tests with total length " + testSuite.totalLengthOfTestCases());

		// TODO: In the end we will only need one analysis technique
		if (!Properties.ANALYSIS_CRITERIA.isEmpty()) {
			// SearchStatistics.getInstance().addCoverage(Properties.CRITERION.toString(),
			// coverage);
			CoverageCriteriaAnalyzer.analyzeCriteria(testSuite, Properties.ANALYSIS_CRITERIA);
			// FIXME: can we send all bestSuites?
		}
		if (Properties.CRITERION.length > 1)
			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Resulting test suite's coverage: "
                    + NumberFormat.getPercentInstance().format(coverage)
                    + " (average coverage for all fitness functions)");
		else
			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Resulting test suite's coverage: "
                    + NumberFormat.getPercentInstance().format(coverage));

		// printBudget(ga); // TODO - need to move this somewhere else
		if (ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE) && Properties.ANALYSIS_CRITERIA.isEmpty())
			DefUseCoverageSuiteFitness.printCoverage();

		DSEStats.getInstance().trackConstraintTypes();

		DSEStats.getInstance().trackSolverStatistics();

		if (Properties.DSE_PROBABILITY > 0.0 && Properties.LOCAL_SEARCH_RATE > 0
				&& Properties.LOCAL_SEARCH_PROBABILITY > 0.0) {
			DSEStats.getInstance().logStatistics();
		}

		if (Properties.FILTER_SANDBOX_TESTS) {
			for (TestChromosome test : testSuite.getTestChromosomes()) {
				// delete all statements leading to security exceptions
				ExecutionResult result = test.getLastExecutionResult();
				if (result == null) {
					result = TestCaseExecutor.runTest(test.getTestCase());
				}
				if (result.hasSecurityException()) {
					int position = result.getFirstPositionOfThrownException();
					if (position > 0) {
						test.getTestCase().chop(position);
						result = TestCaseExecutor.runTest(test.getTestCase());
						test.setLastExecutionResult(result);
					}
				}
			}
		}
		buildAssert(testSuite);


		if(Properties.NO_RUNTIME_DEPENDENCY) {
			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Property NO_RUNTIME_DEPENDENCY is set to true - skipping JUnit compile check");
			LoggingUtils.getSmartUtLogger().info("* " +ClientProcess.getPrettyPrintIdentifier()
                    + "WARNING: Not including the runtime dependencies is likely to lead to flaky tests!");
		}
        else if (Properties.JUNIT_TESTS && (Properties.JUNIT_CHECK == Properties.JUnitCheckValues.TRUE ||
                Properties.JUNIT_CHECK == Properties.JUnitCheckValues.OPTIONAL)) {
			logger.warn("Start JUNIT COMPILE AND CHECK, test suite size is {}", testSuite.size());
            if(ClassPathHacker.isJunitCheckAvailable())
                compileAndCheckTests(testSuite);
            else
                logger.warn("Cannot run Junit test. Cause {}",ClassPathHacker.getCause());
			logger.warn("JUNIT COMPILE AND CHECK DONE");
        }
	}

	/**
	 * Compile and run the given tests. Remove from input list all tests that do
	 * not compile, and handle the cases of instability (either remove tests or
	 * comment out failing assertions)
	 *
	 * @param chromosome
	 */
	private void compileAndCheckTests(TestSuiteChromosome chromosome) {
		LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Compiling and checking tests");

		if (!JUnitAnalyzer.isJavaCompilerAvailable()) {
			String msg = "No Java compiler is available. Make sure to run SmartUt with the JDK and not the JRE."
					+ "You can try to setup the JAVA_HOME system variable to point to it, as well as to make sure that the PATH "
					+ "variable points to the JDK before any JRE.";
			logger.error(msg);
			throw new RuntimeException(msg);
		}

		ClientServices.getInstance().getClientNode().changeState(ClientState.JUNIT_CHECK);

		// Store this value; if this option is true then the JUnit check
		// would not succeed, as the JUnit classloader wouldn't find the class
		boolean junitSeparateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
		Properties.USE_SEPARATE_CLASSLOADER = false;

		int numUnstable = 0;

		// note: compiling and running JUnit tests can be very time consuming
		if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
			Properties.USE_SEPARATE_CLASSLOADER = junitSeparateClassLoader;
			return;
		}

		List<TestCase> testCases = chromosome.getTests(); // make copy of
															// current tests

		// first, let's just get rid of all the tests that do not compile
		JUnitAnalyzer.removeTestsThatDoNotCompile(testCases);

		// compile and run each test one at a time. and keep track of total time
		long start = java.lang.System.currentTimeMillis();
		Iterator<TestCase> iter = testCases.iterator();
		while (iter.hasNext()) {
			if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
				break;
			}
			TestCase tc = iter.next();
			List<TestCase> list = new ArrayList<>();
			list.add(tc);
			numUnstable += JUnitAnalyzer.handleTestsThatAreUnstable(list);
			if (list.isEmpty()) {
				// if the test was unstable and deleted, need to remove it from
				// final testSuite
				iter.remove();
			}
		}
		/*
		 * compiling and running each single test individually will take more
		 * than compiling/running everything in on single suite. so it can be
		 * used as an upper bound
		 */
		long delta = java.lang.System.currentTimeMillis() - start;

		numUnstable += checkAllTestsIfTime(testCases, delta);

		// second passage on reverse order, this is to spot dependencies among
		// tests
		if (testCases.size() > 1) {
			Collections.reverse(testCases);
			numUnstable += checkAllTestsIfTime(testCases, delta);
		}

		chromosome.clearTests(); // remove all tests
		for (TestCase testCase : testCases) {
			chromosome.addTest(testCase); // add back the filtered tests
		}

		boolean unstable = (numUnstable > 0);

		if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
			logger.warn("JUnit checking timed out");
		}

		ClientServices.track(RuntimeVariable.HadUnstableTests, unstable);
		ClientServices.track(RuntimeVariable.NumUnstableTests, numUnstable);
		Properties.USE_SEPARATE_CLASSLOADER = junitSeparateClassLoader;

	}

	private void buildAssert(TestSuiteChromosome testSuite){
		try {
			//Get the class that makes the ut case
			Class<?> targetClass = Class.forName(Properties.TARGET_CLASS, false, TestGenerationContext.getInstance().getClassLoaderForSUT());

			//Determine whether to add Assert according to Properties and class type
			if (Properties.ASSERTIONS && !targetClass.isEnum()) {
				LoggingUtils.getSmartUtLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier() + "Generating assertions");
				logger.warn("Start adding assertions");
				ClientServices.getInstance().getClientNode().changeState(ClientState.ASSERTION_GENERATION);
				if (TimeController.getInstance().hasTimeToExecuteATestCase()) {
					TestSuiteGeneratorHelper.addAssertions(testSuite);
				}else {
					LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
							+ "Skipping assertion generation because not enough time is left");
				}
				StatisticsSender.sendIndividualToMaster(testSuite); // FIXME: can we
				logger.warn("Add assertions DONE");
			}
		}catch (ClassNotFoundException e){
			logger.error("add assert : targetClass not found ",e);
		}
	}

	private static int checkAllTestsIfTime(List<TestCase> testCases, long delta) {
		if (TimeController.getInstance().hasTimeToExecuteATestCase()
				&& TimeController.getInstance().isThereStillTimeInThisPhase(delta)) {
			return JUnitAnalyzer.handleTestsThatAreUnstable(testCases);
		}
		return 0;
	}

	private TestSuiteChromosome generateTests() {
		// Make sure target class is loaded at this point
		TestCluster.getInstance();

		ContractChecker checker = null;
		if (Properties.CHECK_CONTRACTS) {
			checker = new ContractChecker();
			TestCaseExecutor.getInstance().addObserver(checker);
		}

		TestGenerationStrategy strategy = TestSuiteGeneratorHelper.getTestGenerationStrategy();
		TestSuiteChromosome testSuite = strategy.generateTests();

		if (Properties.CHECK_CONTRACTS) {
			TestCaseExecutor.getInstance().removeObserver(checker);
		}

		StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
		TestSuiteGeneratorHelper.getBytecodeStatistics();

		ClientServices.getInstance().getClientNode().publishPermissionStatistics();

		writeObjectPool(testSuite);

		/*
		 * PUTGeneralizer generalizer = new PUTGeneralizer(); for (TestCase test
		 * : tests) { generalizer.generalize(test); // ParameterizedTestCase put
		 * = new ParameterizedTestCase(test); }
		 */

		return testSuite;
	}

	/**
	 * <p>
	 * If Properties.JUNIT_TESTS is set, this method writes the given test cases
	 * to the default directory Properties.TEST_DIR.
	 * 
	 * <p>
	 * The name of the test will be equal to the SUT followed by the given
	 * suffix
	 * 
	 * @param testSuite
	 *            a test suite.
	 */
	public static TestGenerationResult writeJUnitTestsAndCreateResult(TestSuiteChromosome testSuite, String suffix) {
		List<TestCase> tests = testSuite.getTests();
		if (Properties.JUNIT_TESTS) {
			ClientServices.getInstance().getClientNode().changeState(ClientState.WRITING_TESTS);

			TestSuiteWriter suiteWriter = new TestSuiteWriter();
			suiteWriter.insertTests(tests);

			String name = Properties.TARGET_CLASS.substring(Properties.TARGET_CLASS.lastIndexOf(".") + 1);
			String testDir = Properties.TEST_DIR;

			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Writing JUnit test case '"
                    + (name + suffix) + "' to " + testDir);
			suiteWriter.writeTestSuite(name + suffix, testDir, testSuite.getLastExecutionResults());
		}
		return TestGenerationResultBuilder.buildSuccessResult();
	}

	/**
	 * 
	 * @param testSuite
	 *            the test cases which should be written to file
	 */
	public static TestGenerationResult writeJUnitTestsAndCreateResult(TestSuiteChromosome testSuite) {
		return writeJUnitTestsAndCreateResult(testSuite, Properties.JUNIT_SUFFIX);
	}

	public void writeJUnitFailingTests() {
		if (!Properties.CHECK_CONTRACTS)
			return;

		FailingTestSet.sendStatistics();

		if (Properties.JUNIT_TESTS) {

			TestSuiteWriter suiteWriter = new TestSuiteWriter();
			//suiteWriter.insertTests(FailingTestSet.getFailingTests());

			TestSuiteChromosome suite = new TestSuiteChromosome();
			for(TestCase test : FailingTestSet.getFailingTests()) {
				test.setFailing();
				suite.addTest(test);
			}

			String name = Properties.TARGET_CLASS.substring(Properties.TARGET_CLASS.lastIndexOf(".") + 1);
			String testDir = Properties.TEST_DIR;
			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Writing failing test cases '"
                    + (name + Properties.JUNIT_SUFFIX) + "' to " + testDir);
			suiteWriter.insertAllTests(suite.getTests());
			FailingTestSet.writeJUnitTestSuite(suiteWriter);

			suiteWriter.writeTestSuite(name + Properties.JUNIT_FAILED_SUFFIX, testDir, suite.getLastExecutionResults());
		}
	}

	private void writeObjectPool(TestSuiteChromosome suite) {
		if (!Properties.WRITE_POOL.isEmpty()) {
			LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Writing sequences to pool");
			ObjectPool pool = ObjectPool.getPoolFromTestSuite(suite);
			pool.writePool(Properties.WRITE_POOL);
		}
	}

	/**
	 * <p>
	 * getFitnessFunctions
	 * </p>
	 * 
	 * @return a list of {@link org.smartut.testsuite.TestSuiteFitnessFunction}
	 *         objects.
	 */
	public static List<TestSuiteFitnessFunction> getFitnessFunctions() {
		List<TestSuiteFitnessFunction> ffs = new ArrayList<>();
		for (int i = 0; i < Properties.CRITERION.length; i++) {
			ffs.add(FitnessFunctions.getFitnessFunction(Properties.CRITERION[i]));
		}

		return ffs;
	}

	/**
	 * Prints out all information regarding this GAs stopping conditions
	 * 
	 * So far only used for testing purposes in TestSuiteGenerator
	 */
	public void printBudget(GeneticAlgorithm<?> algorithm) {
		LoggingUtils.getSmartUtLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Search Budget:");
		for (StoppingCondition<?> sc : algorithm.getStoppingConditions())
			LoggingUtils.getSmartUtLogger().info("\t- " + sc.toString());
	}

	/**
	 * <p>
	 * getBudgetString
	 * </p>
	 * 
	 * @return a {@link java.lang.String} object.
	 */
	public String getBudgetString(GeneticAlgorithm<?> algorithm) {
		String r = "";
		for (StoppingCondition<?> sc : algorithm.getStoppingConditions())
			r += sc.toString() + " ";

		return r;
	}

	/**
	 * <p>
	 * getFitnessFactories
	 * </p>
	 * 
	 * @return a list of {@link org.smartut.coverage.TestFitnessFactory}
	 *         objects.
	 */
	public static List<TestFitnessFactory<? extends TestFitnessFunction>> getFitnessFactories() {
		List<TestFitnessFactory<? extends TestFitnessFunction>> goalsFactory = new ArrayList<>();
		for (int i = 0; i < Properties.CRITERION.length; i++) {
			goalsFactory.add(FitnessFunctions.getFitnessFactory(Properties.CRITERION[i]));
		}

		return goalsFactory;
	}

	/**
	 * <p>
	 * main
	 * </p>
	 * 
	 * @param args
	 *            an array of {@link java.lang.String} objects.
	 */
	public static void main(String[] args) {
		TestSuiteGenerator generator = new TestSuiteGenerator();
		generator.generateTestSuite();
		System.exit(0);
	}

}
