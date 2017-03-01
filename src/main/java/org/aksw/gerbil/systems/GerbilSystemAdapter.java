package org.aksw.gerbil.systems;

import java.io.IOException;
import java.util.List;

import org.aksw.gerbil.annotator.AnnotatorConfiguration;
import org.aksw.gerbil.annotator.QASystem;
import org.aksw.gerbil.datatypes.ExperimentType;
import org.aksw.gerbil.exceptions.GerbilException;
import org.aksw.gerbil.io.nif.impl.TurtleNIFParser;
import org.aksw.gerbil.io.nif.impl.TurtleNIFWriter;
import org.aksw.gerbil.transfer.nif.Document;
import org.aksw.gerbil.web.config.AdapterList;
import org.aksw.gerbil.web.config.AnnotatorsConfig;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.hobbit.core.components.AbstractSystemAdapter;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.hobbit.utils.rdf.RdfHelper;
import org.hobbit.vocab.HOBBIT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GerbilSystemAdapter extends AbstractSystemAdapter {
	private static final Logger LOGGER = LoggerFactory.getLogger(GerbilSystemAdapter.class);
	public static final Property ANNOTATOR_NAME_PROPERTY = ResourceFactory.createProperty("http://w3id.org/gerbil/hobbit/vocab#annotatorName");

	private QASystem qasystem;
	private TurtleNIFParser parser;
	private TurtleNIFWriter writer;

	public GerbilSystemAdapter() {
		parser = new TurtleNIFParser();
		writer = new TurtleNIFWriter();
	}

	@Override
	public void init() throws Exception {
		super.init();
		/**
		 * Retrieve annotator name from Hobbit system meta data file
		 */
		List<Resource> rdfSystemInstanceSubjectList = RdfHelper.getSubjectResources(this.systemParamModel, RDF.type, HOBBIT.SystemInstance);

		if (rdfSystemInstanceSubjectList.size() == 0) {
			throw new Exception("Couldn't find a SystemInstance defined in sytem metadata file");	
		} else if (rdfSystemInstanceSubjectList.size() > 0) {
			throw new Exception("More than one SystemInstance defined in sytem metadata file - proceeding with first occurrence");
		}

		Literal annotatorNameLiteral = RdfHelper.getLiteral(systemParamModel, rdfSystemInstanceSubjectList.get(0), ANNOTATOR_NAME_PROPERTY);

		/**
		 * Get annotators from GERBIL and find the one with matching name
		 */
		AdapterList<AnnotatorConfiguration> adapterList = AnnotatorsConfig.annotators();

		List<AnnotatorConfiguration> adapterConfigForQAList = adapterList.getAdaptersForExperiment(ExperimentType.QA);

		AnnotatorConfiguration adapterConfigWithFittingAnnotatorName = null;

		for (AnnotatorConfiguration it : adapterConfigForQAList) {
			if (it.getName().equalsIgnoreCase(annotatorNameLiteral.getString())) {
				adapterConfigWithFittingAnnotatorName = it;
				break;
			}
		}

		if (adapterConfigWithFittingAnnotatorName == null) {
			throw new Exception("Could'nt find a GERBIL QASystem annotator with name: |" + annotatorNameLiteral.getString() + "|");
		}
		/**
		 * Retrieve annotator.
		 */
		qasystem = (QASystem) adapterConfigWithFittingAnnotatorName.getAnnotator(ExperimentType.QA);

	}

	public void receiveGeneratedData(byte[] arg0) {
		// Nothing to handle here.
	}

	public void receiveGeneratedTask(String taskIdString, byte[] data) {
		List<Document> documents = parser.parseNIF(RabbitMQUtils.readString(data));
		try {
			documents.get(0).setMarkings(qasystem.answerQuestion(documents.get(0)));
			sendResultToEvalStorage(taskIdString, RabbitMQUtils.writeString(writer.writeNIF(documents)));
		} catch (GerbilException e) {
			LOGGER.error("QASystem " + qasystem.getName() + " wasn't able to answer a Question", e);
		} catch (IOException e) {
			LOGGER.error("Couldn't send data to EvalStorage", e);
		} catch (NullPointerException e) {
			LOGGER.error("Couldn't parse task or task was empty");
		}

	}

	@Override
	public void close() throws IOException {

		qasystem.close();
		super.close();
	}

}
