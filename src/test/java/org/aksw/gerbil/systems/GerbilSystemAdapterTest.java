package org.aksw.gerbil.systems;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.aksw.gerbil.annotator.QASystem;
import org.aksw.gerbil.annotator.impl.AbstractAnnotator;
import org.aksw.gerbil.exceptions.GerbilException;
import org.aksw.gerbil.qa.QALDStreamType;
import org.aksw.gerbil.qa.QALDStreamUtils;
import org.aksw.gerbil.transfer.nif.Document;
import org.aksw.gerbil.transfer.nif.Marking;
import org.apache.commons.io.FileUtils;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.hsqldb.lib.StringInputStream;
import org.junit.Assert;
import org.junit.Test;

public class GerbilSystemAdapterTest extends GerbilSystemAdapter {

    private static final String TEST_QUESTION_FILE = "src/test/resources/test_question.json";
    private static final String TASK_ID = "19";

    private String questionJSON;

    @Test
    public void test() throws Exception {
        questionJSON = FileUtils.readFileToString(new File(TEST_QUESTION_FILE));

        this.setAnnotator(new DummyAnnotator());

        receiveGeneratedTask(TASK_ID, RabbitMQUtils.writeString(questionJSON));
    }

    @Override
    protected void sendResultToEvalStorage(String taskIdString, byte[] data) throws IOException {
        Assert.assertEquals(TASK_ID, taskIdString);
        String responseJSON = RabbitMQUtils.readString(data);

        List<Document> expected = QALDStreamUtils.parseDocument(new StringInputStream(questionJSON),
                QALDStreamType.JSON, "check");
        List<Document> received = QALDStreamUtils.parseDocument(new StringInputStream(responseJSON),
                QALDStreamType.JSON, "check");

        Assert.assertEquals(expected.size(), received.size());
        for (int i = 0; i < expected.size(); ++i) {
            Assert.assertEquals(expected.get(i), received.get(i));
        }
    }

    protected static class DummyAnnotator extends AbstractAnnotator implements QASystem {
        @Override
        public List<Marking> answerQuestion(Document document, String lang) throws GerbilException {
            return document.getMarkings();
        }
    }
}
