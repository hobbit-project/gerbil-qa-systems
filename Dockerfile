

FROM java

ADD target/gerbil-qa-systems-0.0.1-SNAPSHOT.jar /gerbil/gerbil-qa-systems-0.0.1-SNAPSHOT.jar

WORKDIR /gerbil

CMD java -cp gerbil-qa-systems-0.0.1-SNAPSHOT.jar org.hobbit.core.run.ComponentStarter org.aksw.gerbil.systems.GerbilSystemAdapter