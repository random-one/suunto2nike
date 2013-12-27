package com.oldhu.suunto2nike.suunto.moveslink2;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math.ArgumentOutsideDomainException;
import org.apache.commons.math.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math.analysis.polynomials.PolynomialSplineFunction;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.oldhu.suunto2nike.suunto.SuuntoMove;

public class XMLParser
{
	private static Log log = LogFactory.getLog(XMLParser.class);
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	private SuuntoMove suuntoMove = new SuuntoMove();
	private boolean hasGPS = false;
	private boolean parseCompleted = false;
	
	public boolean isParseCompleted() {
		return parseCompleted;
	}
	
	public SuuntoMove getSuuntoMove() {
		return suuntoMove;
	}

	private Document getDocument(File xmlFile) throws Exception
	{
		BufferedReader in = new BufferedReader(new FileReader(xmlFile));
		String firstLine = in.readLine();
		if (!firstLine.trim().toLowerCase().equals("<?xml version=\"1.0\" encoding=\"utf-8\"?>")) {
			in.close();
			throw new Exception("File format not correct: " + xmlFile.getName());
		}

		StringBuilder sb = new StringBuilder();
		sb.append(firstLine);
		sb.append("<data>");
		while (in.ready()) {
			sb.append(in.readLine());
		}
		in.close();
		sb.append("</data>");
		InputStream stream = new ByteArrayInputStream(sb.toString().getBytes("UTF-8"));

		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		return dBuilder.parse(stream);

	}

	private boolean pareseHeader(Element header) throws ParseException
	{
		int moveType = Integer.parseInt(getChildElementValue(header, "ActivityType"));
		if (moveType != 3 && moveType != 93) {
			log.info("    not a running move");
			return false;
		}
		int distance = Integer.parseInt(getChildElementValue(header, "Distance"));
		if (distance == 0) {
			log.info("    distance zero");
			return false;
		}
		
		suuntoMove.setDistance(distance);
		suuntoMove.setDuration((int) (doubleFromString(getChildElementValue(header, "Duration")).doubleValue() * 1000));
		suuntoMove.setCalories(caloriesFromKilojoules(doubleFromString(getChildElementValue(header, "Energy"))));
		
		String dateTime = getChildElementValue(header, "DateTime");
		suuntoMove.setStartTime(dateFormat.parse(dateTime));
		
		return true;
	}
	
	private int caloriesFromKilojoules(double kj)
	{
		return (int) (kj / 4186);
	}

	public XMLParser(File xmlFile) throws Exception
	{
		log.debug("Parsing " + xmlFile.getName());
		Document doc = getDocument(xmlFile);
		Element header = (Element) doc.getElementsByTagName("header").item(0);
		if (pareseHeader(header)) {
			Element samples = (Element) doc.getElementsByTagName("Samples").item(0);
			checkGPSInfo(samples);
			if (!hasGPSInfo()) {
				parseSamples(samples);							
			} else {
				log.info("    has GPS info, skip");
			}
		}
	}
	
	private boolean parseSamples(Element samples) throws ArgumentOutsideDomainException
	{
		NodeList sampleList = samples.getElementsByTagName("Sample");
		
		boolean isSampleStarted = false;
		ArrayList<Double> timeList = new ArrayList<Double>();
		ArrayList<Double> hrList = new ArrayList<Double>();
		ArrayList<Double> distanceList = new ArrayList<Double>();
		
		
		for (int i = 0; i < sampleList.getLength(); ++i) {
			Element sample = (Element) sampleList.item(i);
			if (!isSampleStarted) {
				String cadenceSource = getChildElementValue(sample, "Events", "Cadence", "Source");
				if (cadenceSource != null) {
					if (cadenceSource.equalsIgnoreCase("FootPod")) {
						log.info("    Cadence source is FootPod");
						continue;
					}
					return false;
				}
				String distanceSource = getChildElementValue(sample, "Events", "Distance", "Source");
				if (distanceSource != null) {
					if (distanceSource.equalsIgnoreCase("FootPod")) {
						log.info("    Distance source is FootPod");
						isSampleStarted = true;
						continue;
					}
					return false;
				}
				continue;	
			}
			
			// Now start with the samples
			
			// Event sample, skip it
			if (sample.getElementsByTagName("Events").getLength() != 0) {
				continue;
			}
			
			timeList.add(doubleFromString(getChildElementValue(sample, "Time")));
			hrList.add(doubleFromString(getChildElementValue(sample, "HR")));
			distanceList.add(doubleFromString(getChildElementValue(sample, "Distance")));
		}

		double[] timeArray = new double[timeList.size()];
		double[] hrArray = new double[hrList.size()];
		double[] distanceArray = new double[distanceList.size()];
		
		populateTimeArray(timeArray, timeList);
		populateHRArray(hrArray, hrList, timeArray);
		populateDistanceArray(distanceArray, distanceList);
	
		PolynomialSplineFunction timeToHR = generateTimeToHRSplineFunction(timeArray, hrArray);
		PolynomialSplineFunction timeToDistance = generateTimeToDistanceSplineFunction(timeArray, distanceArray);
		
		double t = 0;
		while (t < suuntoMove.getDuration()) {
			t += 10 * 1000;
			int hr = (int) interpolate(timeToHR, t);
			int distance = (int) interpolate(timeToDistance, t);
			suuntoMove.addHeartRateSample(Integer.toString(hr));
			suuntoMove.addDistanceSample(Integer.toString(distance));
		}
		
		parseCompleted = true;
		
		return true;
	}
	
	private double interpolate(PolynomialSplineFunction spline, double x) throws ArgumentOutsideDomainException {
		try {
			return spline.value(x);
		}
		catch (ArgumentOutsideDomainException aode) {
			double[] knots = spline.getKnots();
			return spline.value(knots[(x < knots[0]) ? 0 : spline.getN() -1]);
		}
	}
	
	private PolynomialSplineFunction generateTimeToDistanceSplineFunction(double[] timeArray, double[] distanceArray)
	{
		SplineInterpolator interpolator = new SplineInterpolator();
		return interpolator.interpolate(timeArray, distanceArray);
	}

	private void populateDistanceArray(double[] distanceArray, ArrayList<Double> distanceList)
	{
		for (int i = 0; i < distanceList.size(); ++i) {
			distanceArray[i] = distanceList.get(i).doubleValue();
		}		
	}

	private void populateHRArray(double[] hrArray, ArrayList<Double> hrList, double[] timeArray)
	{
		double start = 0;
		for (int i = 0; i < hrList.size(); ++i) {
//			double time = timeArray[i] - start;
//			start = timeArray[i];
			hrArray[i] = hrList.get(i).doubleValue() * 60;
		}
	}

	private void populateTimeArray(double[] timeArray, ArrayList<Double> timeList)
	{
		for (int i = 0; i < timeList.size(); ++i) {
			timeArray[i] = timeList.get(i).doubleValue() * 1000;
		}
	}

	private PolynomialSplineFunction generateTimeToHRSplineFunction(double[] timeArray, double[] hrArray)
	{
		SplineInterpolator interpolator = new SplineInterpolator();
		return interpolator.interpolate(timeArray, hrArray);
	}

	private void checkGPSInfo(Element samples)
	{
		NodeList sampleList = samples.getElementsByTagName("Sample");
		for (int i = 0; i < sampleList.getLength(); ++i) {
			Element sample = (Element) sampleList.item(i);
			String sampleType = getChildElementValue(sample, "SampleType");
			if (sampleType != null) {
				if (sampleType.toLowerCase().contains("gps")) {
					hasGPS = true;
					return;
				}
			}
		}
		hasGPS = false;
	}

	public boolean hasGPSInfo()
	{
		return hasGPS;
	}
	
	private Double doubleFromString(String str)
	{
		if (str == null) {
			return Double.valueOf(0);
		}
		return Double.valueOf(str);
	}
	
	private String getChildElementValue(Element parent, String... elementNames)
	{
		for (int i = 0; i < elementNames.length; ++i) {
			String elementName = elementNames[i];
			NodeList nodeList = parent.getElementsByTagName(elementName);
			if (nodeList.getLength() != 1) return null;
			Element child = (Element) nodeList.item(0);
			if (i == elementNames.length - 1) {
				return child.getTextContent();				
			}
			parent = child;
		}
		return null;
	}
}