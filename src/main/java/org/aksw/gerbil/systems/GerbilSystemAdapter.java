package org.aksw.gerbil.systems;

import java.io.IOException;
import java.util.List;
import java.util.Vector;

import org.aksw.gerbil.annotator.A2KBAnnotator;
import org.aksw.gerbil.annotator.Annotator;
import org.aksw.gerbil.annotator.AnnotatorConfiguration;
import org.aksw.gerbil.annotator.C2KBAnnotator;
import org.aksw.gerbil.annotator.D2KBAnnotator;
import org.aksw.gerbil.annotator.EntityRecognizer;
import org.aksw.gerbil.annotator.EntityTyper;
import org.aksw.gerbil.annotator.OKETask1Annotator;
import org.aksw.gerbil.annotator.OKETask2Annotator;
import org.aksw.gerbil.annotator.QASystem;
import org.aksw.gerbil.datatypes.ExperimentType;
import org.aksw.gerbil.io.nif.impl.TurtleNIFParser;
import org.aksw.gerbil.io.nif.impl.TurtleNIFWriter;
import org.aksw.gerbil.transfer.nif.Document;
import org.aksw.gerbil.transfer.nif.Marking;
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
	public static final Property ANNOTATOR_EXPERIMENT_TYPE_PROPERTY = ResourceFactory.createProperty("http://w3id.org/gerbil/hobbit/vocab#applicableTo");

	private Annotator annotator;
	private ExperimentType experimentType;
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
			throw new Exception("More than one SystemInstance defined in sytem metadata file");
		}

		Literal annotatorNameLiteral = RdfHelper.getLiteral(systemParamModel, rdfSystemInstanceSubjectList.get(0), ANNOTATOR_NAME_PROPERTY);
		Literal annotatorExperimentType = RdfHelper.getLiteral(systemParamModel, rdfSystemInstanceSubjectList.get(0), ANNOTATOR_EXPERIMENT_TYPE_PROPERTY);
		if (annotatorExperimentType == null) {
			throw new Exception("No experiment type defiend for annotator |" + annotatorNameLiteral.toString());
		}

		try {
			/**
			 * Discard any number representation which can be interpreted as
			 * year
			 */
			String annotatorExperimentTypeStr = annotatorExperimentType.toString().replaceAll("[^0-9]+|^)[1,2]{1}[0-9]{3}([^0-9]+|$)", "$1$2");
			experimentType = ExperimentType.valueOf(annotatorExperimentTypeStr.trim());
		} catch (Exception e) {
			throw new Exception("Cannot find an ExperimentType enum for input |" + annotatorExperimentType.toString());
		}

		/**
		 * Get annotators from GERBIL and find the one with matching name
		 */
		AdapterList<AnnotatorConfiguration> adapterList = AnnotatorsConfig.annotators();

		List<AnnotatorConfiguration> adapterConfigForExpTypeList = adapterList.getAdaptersForExperiment(experimentType);

		AnnotatorConfiguration adapterConfigWithFittingAnnotatorName = null;

		for (AnnotatorConfiguration it : adapterConfigForExpTypeList) {
			if (it.getName().equalsIgnoreCase(annotatorNameLiteral.getString())) {
				adapterConfigWithFittingAnnotatorName = it;
				break;
			}
		}

		if (adapterConfigWithFittingAnnotatorName == null) {
			throw new Exception("Could'nt find a GERBIL annotator with name: |" + annotatorNameLiteral.getString() + "|");
		}
		/**
		 * Retrieve annotator.
		 */

		annotator = adapterConfigWithFittingAnnotatorName.getAnnotator(experimentType);

	}
	
	public void receiveGeneratedData(byte[] arg0) {
		// Nothing to handle here.
	}

	public void receiveGeneratedTask(String taskIdString, byte[] data) {
		List<Document> documents = parser.parseNIF(RabbitMQUtils.readString(data));
		try {
			GerbilSystemAdapter.answerQuestion(annotator, documents.get(0), experimentType);
			sendResultToEvalStorage(taskIdString, RabbitMQUtils.writeString(writer.writeNIF(documents)));
		} catch (IOException e) {
			LOGGER.error("Couldn't send data to EvalStorage", e);
		} catch (NullPointerException e) {
			LOGGER.error("Couldn't parse task or task was empty");
		}catch(Exception e){
		LOGGER.error("QASystem " + annotator.getName() + " wasn't able to answer a Question", e);
		}
	}

	public static void answerQuestion(Annotator annotator,Document doc,ExperimentType experimentType) throws Exception {
		switch (experimentType) {
		case A2KB:
			A2KBAnnotator a2kb=(A2KBAnnotator) annotator;
			doc.setMarkings(new Vector<Marking>(a2kb.performA2KBTask(doc)));
			break;
		case C2KB:
			C2KBAnnotator c2kb= (C2KBAnnotator) annotator;
			doc.setMarkings(new Vector<Marking>(c2kb.performC2KB(doc)));
			break;
		case D2KB:
			D2KBAnnotator d2kb= (D2KBAnnotator)annotator;
			doc.setMarkings(new Vector<Marking>(d2kb.performD2KBTask(doc)));
			break;
		case ERec:
			EntityRecognizer erec=(EntityRecognizer) annotator;
			doc.setMarkings(new Vector<Marking>(erec.performRecognition(doc)));
			break;
		case ETyping:
			EntityTyper etyp=(EntityTyper)annotator;
			doc.setMarkings(new Vector<Marking>(etyp.performTyping(doc)));
			break;
		case OKE_Task1:
			OKETask1Annotator oke1=(OKETask1Annotator) annotator;
			doc.setMarkings(new Vector<Marking>(oke1.performTask1(doc)));
			break;
		case OKE_Task2:
			OKETask2Annotator oke2= (OKETask2Annotator)annotator;
			doc.setMarkings(new Vector<Marking>(oke2.performTask2(doc)));
			break;
		case QA:
			QASystem qa= (QASystem) annotator;
			doc.setMarkings(new Vector<Marking>(qa.answerQuestion(doc)));
			break;
			
		case RE2KB: // Fall-through
		case AIT2KB:
		case AType:
		case P2KB:
		default:
			throw new Exception("Experiment type "+experimentType.toString() + "is not supported");

		}

	}
	
	public void setAnnotator(Annotator annotator) {
        this.annotator = annotator;
    }

	@Override
	public void close() throws IOException {

		annotator.close();
		super.close();
	}

}
