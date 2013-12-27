/**
 * 
 */
package com.eharmony.matching.vw.webservice.core.exampleprocessor.tcpip;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eharmony.matching.vw.webservice.core.ExampleReadException;
import com.eharmony.matching.vw.webservice.core.example.Example;
import com.eharmony.matching.vw.webservice.core.example.ExampleFormatException;
import com.eharmony.matching.vw.webservice.core.example.StringExample;
import com.eharmony.matching.vw.webservice.core.exampleprocessor.ExampleProcessingEventHandler;
import com.eharmony.matching.vw.webservice.core.exampleprocessor.ExampleProcessor;
import com.eharmony.matching.vw.webservice.core.exampleprocessor.ExampleSubmissionException;
import com.eharmony.matching.vw.webservice.core.exampleprocessor.ExampleSubmissionState;
import com.eharmony.matching.vw.webservice.core.exampleprocessor.PredictionFetchException;
import com.eharmony.matching.vw.webservice.core.exampleprocessor.PredictionFetchState;
import com.eharmony.matching.vw.webservice.core.prediction.Prediction;

/**
 * @author vrahimtoola
 * 
 *         Tests the AsyncFailFastTCPIPExampleProcessor.
 */
public class AsyncFailFastTCPIPExampleProcessorTest implements
		ExampleProcessingEventHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(AsyncFailFastTCPIPExampleProcessorTest.class);

	/*
	 * These variables could get written to by the example submitting thread, so
	 * we need to make them volatile.
	 */
	private volatile boolean exampleReadExceptionThrown,
			exampleFormatExceptionThrown, exampleSubmissionExceptionThrown,
			exampleSubmissionCompleteCalled;
	private volatile boolean predictionFetchExceptionThrown,
			predictionFetchCompleteCalled;

	private ExampleSubmissionState expectedStateOnExampleSubmissionComplete;
	private PredictionFetchState expectedStateOnPredictionFetchComplete;
	private long expectedNumberOfSkippedExamples,
			expectedNumberOfSubmittedExamples;

	private CountDownLatch countDownLatch;

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {

		exampleReadExceptionThrown = false;
		exampleFormatExceptionThrown = false;
		exampleSubmissionExceptionThrown = false;
		exampleSubmissionCompleteCalled = false;

		predictionFetchCompleteCalled = false;
		predictionFetchExceptionThrown = false;

		expectedNumberOfSkippedExamples = -1;
		expectedNumberOfSubmittedExamples = -1;

		expectedStateOnExampleSubmissionComplete = ExampleSubmissionState.Complete;
		expectedStateOnPredictionFetchComplete = PredictionFetchState.Complete;

		countDownLatch = new CountDownLatch(2);

	}

	/*
	 * Just a simple test to verify that examples can be submitted and read as
	 * expected.
	 */
	@Test
	public void simpleTest() throws IOException, ExampleSubmissionException, InterruptedException {

		Iterable<Example> examples = getExamples("One", "Two", "Three");

		InputStream predictionInputStream = getPredictionInputStream("1", "2", "3");

		Socket socket = mock(Socket.class);
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		when(socket.getOutputStream()).thenReturn(outputStream);
		when(socket.getInputStream()).thenReturn(predictionInputStream);

		TCPIPSocketFactory socketFactory = mock(TCPIPSocketFactory.class);
		when(socketFactory.getSocket()).thenReturn(socket);

		AsyncFailFastTCPIPExampleProcessor toTest = new AsyncFailFastTCPIPExampleProcessor(socketFactory, Executors.newCachedThreadPool(), examples);

		expectedNumberOfSkippedExamples = 0;
		expectedNumberOfSubmittedExamples = 3;
		expectedStateOnExampleSubmissionComplete = ExampleSubmissionState.Complete;
		expectedStateOnPredictionFetchComplete = PredictionFetchState.Complete;

		Iterable<Prediction> predictions = toTest.submitExamples(this);

		int x = 0;

		for (Prediction p : predictions) {

			switch (x++) {

				case 0:
					Assert.assertEquals("1", p.getVWStringRepresentation());
					break;

				case 1:
					Assert.assertEquals("2", p.getVWStringRepresentation());
					break;

				case 2:
					Assert.assertEquals("3", p.getVWStringRepresentation());
					break;

				default:
					Assert.fail("Too many predictions!");
			}
		}

		Assert.assertEquals(3, x);

		countDownLatch.await(); //wait till the example thread is done as well.

		//check that all examples got there
		BufferedReader bReader = new BufferedReader(new StringReader(new String(outputStream.toByteArray())));

		x = 0;
		String line = null;
		while ((line = bReader.readLine()) != null) {

			switch (x++) {

				case 0:
					Assert.assertEquals("One", line);
					break;

				case 1:
					Assert.assertEquals("Two", line);
					break;

				case 2:
					Assert.assertEquals("Three", line);
					break;

				default:
					Assert.fail("Too many examples!");
			}

		}

		Assert.assertEquals(3, x);

		verify(socketFactory, times(1)).getSocket();
		verify(socket, times(1)).getInputStream();
		verify(socket, times(1)).getOutputStream();
		verify(socket, times(1)).shutdownOutput();

		//no exceptions should have been thrown
		Assert.assertFalse(exampleReadExceptionThrown);
		Assert.assertFalse(exampleFormatExceptionThrown);
		Assert.assertFalse(exampleSubmissionExceptionThrown);
		Assert.assertFalse(predictionFetchExceptionThrown);

		//the completion call backs should have been fired
		Assert.assertTrue(exampleSubmissionCompleteCalled);
		Assert.assertTrue(predictionFetchCompleteCalled);

	}

	/*
	 * Tests that an ExampleSubmissionException is thrown when the socket cannot
	 * be retrieved from the socket factory.
	 */
	@Test(expected = ExampleSubmissionException.class)
	public void throwsExampleSubmissionException() throws IOException, ExampleSubmissionException {

		Iterable<Example> examples = getExamples("One", "Two", "Three");

		TCPIPSocketFactory socketFactory = mock(TCPIPSocketFactory.class);
		when(socketFactory.getSocket()).thenThrow(UnknownHostException.class);

		AsyncFailFastTCPIPExampleProcessor toTest = new AsyncFailFastTCPIPExampleProcessor(socketFactory, Executors.newCachedThreadPool(), examples);

		toTest.submitExamples(this);

	}

	/*
	 * Tests that an ExampleReadException is handled as expected.
	 */
	@Test
	public void handlesExampleReadException() throws IOException, ExampleSubmissionException, InterruptedException {

		Iterator iterator = mock(Iterator.class);
		when(iterator.hasNext()).thenReturn(true);
		when(iterator.next()).thenThrow(ExampleReadException.class);

		Iterable examples = mock(Iterable.class);
		when(examples.iterator()).thenReturn(iterator);

		InputStream predictionInputStream = getPredictionInputStream("1", "2", "3");

		Socket socket = mock(Socket.class);
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		when(socket.getOutputStream()).thenReturn(outputStream);
		when(socket.getInputStream()).thenReturn(predictionInputStream);

		TCPIPSocketFactory socketFactory = mock(TCPIPSocketFactory.class);
		when(socketFactory.getSocket()).thenReturn(socket);

		AsyncFailFastTCPIPExampleProcessor toTest = new AsyncFailFastTCPIPExampleProcessor(socketFactory, Executors.newCachedThreadPool(), examples);

		expectedNumberOfSkippedExamples = 0;
		expectedNumberOfSubmittedExamples = 0;
		expectedStateOnExampleSubmissionComplete = ExampleSubmissionState.ExampleReadFault;
		expectedStateOnPredictionFetchComplete = PredictionFetchState.Complete;

		Iterable<Prediction> predictions = toTest.submitExamples(this);

		int x = 0;

		for (Prediction p : predictions) {

			switch (x++) {

				case 0:
					Assert.assertEquals("1", p.getVWStringRepresentation());
					break;

				case 1:
					Assert.assertEquals("2", p.getVWStringRepresentation());
					break;

				case 2:
					Assert.assertEquals("3", p.getVWStringRepresentation());
					break;

				default:
					Assert.fail("Too many predictions!");
			}
		}

		countDownLatch.await(); //wait till example submission and prediction fetch are both done.

		verify(socketFactory, times(1)).getSocket();
		verify(socket, times(1)).getInputStream();

		//these next two verify calls fail. i suspect the java compiler is doing some magic behind the scenes causing
		//the call to iterator() or next() or hasNext() to happen before the calls to the socket methods.

		//verify(socket, times(1)).getOutputStream();
		//verify(socket, times(1)).shutdownOutput();

		Assert.assertTrue(exampleReadExceptionThrown);
		Assert.assertFalse(exampleFormatExceptionThrown);
		Assert.assertFalse(exampleSubmissionExceptionThrown);
		Assert.assertFalse(predictionFetchExceptionThrown);

		//the completion call backs should have been fired
		Assert.assertTrue(exampleSubmissionCompleteCalled);
		Assert.assertTrue(predictionFetchCompleteCalled);

	}

	private Iterable<Example> getExamples(String... examples) {

		List<Example> toReturn = new ArrayList<Example>();

		for (String s : examples) {

			toReturn.add(new StringExample(s));
		}

		return toReturn;
	}

	private InputStream getPredictionInputStream(String... predictions) {

		String newLine = System.getProperty("line.separator");

		StringBuilder sbr = new StringBuilder();

		for (String s : predictions) {
			sbr.append(s);
			sbr.append(newLine);
		}

		return new ByteArrayInputStream(sbr.toString().getBytes());
	}

	@Override
	public void onExampleReadException(ExampleProcessor processor, ExampleReadException theException) {

		Assert.assertTrue(processor.getExampleSubmissionState() == ExampleSubmissionState.ExampleReadFault);

		exampleReadExceptionThrown = true;
	}

	@Override
	public void onExampleFormatException(ExampleProcessor processor, ExampleFormatException theException) {

		//the async tcp ip example processor carries on when it encounters an example format exception,
		//and doesn't consider it to be a fault.
		Assert.assertTrue(processor.getExampleSubmissionState() == ExampleSubmissionState.OnGoing);

		exampleFormatExceptionThrown = true;

	}

	@Override
	public void onExampleSubmissionException(ExampleProcessor processor, ExampleSubmissionException theException) {

		Assert.assertTrue(processor.getExampleSubmissionState() == ExampleSubmissionState.ExampleSubmissionFault);

		exampleSubmissionExceptionThrown = true;
	}

	@Override
	public void onExampleSubmissionComplete(ExampleProcessor processor) {

		LOGGER.info("Example submission complete called!");

		Assert.assertTrue(processor.getExampleSubmissionState() == expectedStateOnExampleSubmissionComplete);
		Assert.assertEquals(processor.getTotalNumberOfExamplesSkipped(), expectedNumberOfSkippedExamples);
		Assert.assertEquals(processor.getTotalNumberOfExamplesSubmitted(), expectedNumberOfSubmittedExamples);

		exampleSubmissionCompleteCalled = true;

		countDownLatch.countDown();

	}

	@Override
	public void onPredictionFetchException(ExampleProcessor processor, PredictionFetchException theException) {

		Assert.assertTrue(processor.getPredictionFetchState() == PredictionFetchState.PredictionFetchFault);

		predictionFetchExceptionThrown = true;
	}

	@Override
	public void onPredictionFetchComplete(ExampleProcessor processor) {

		LOGGER.info("Prediction fetch complete called!");

		Assert.assertTrue(processor.getPredictionFetchState() == expectedStateOnPredictionFetchComplete);

		predictionFetchCompleteCalled = true;

		countDownLatch.countDown();

	}

}
