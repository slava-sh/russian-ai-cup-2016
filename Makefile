NAME=latest
HOST=127.0.0.1
PORT=31001
TOKEN=0000000000000000

ifeq ($(NAME),latest)
	STRATEGY_JAR=./out/strategy.jar
else
	STRATEGY_JAR=./strategies/$(NAME).jar
endif

strategy:
	mkdir -p ./out/strategy
	javac -d ./out/strategy -sourcepath ./src ./src/{Runner,DebugVisualizer}.java
	jar cf $(STRATEGY_JAR) -C ./out/strategy .

strategy-no-debug: clean
	mkdir -p ./out/strategy
	javac -d ./out/strategy -sourcepath ./src ./src/Runner.java
	jar cf $(STRATEGY_JAR) -C ./out/strategy .

plugin:
	mkdir -p ./out/plugin
	javac -d ./out/plugin -sourcepath ./src ./src/LocalTestRendererListener.java

run-simulator:
	java -Xms512m -Xmx2G -server -jar ./vendor/local-runner/local-runner.jar \
		./local-runner.properties

run-strategy:
	java -cp $(STRATEGY_JAR) Runner $(HOST) $(PORT) $(TOKEN)

run-simulator-and-strategy:
	@./run-simulator-and-strategy.sh

clean:
	rm -r ./out
