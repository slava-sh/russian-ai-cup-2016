strategy:
	mkdir -p ./out/strategy
	javac -d ./out/strategy -sourcepath ./src ./src/{Runner,DebugVisualizer}.java
	jar cf ./out/strategy.jar -C ./out/strategy .

strategy-no-debug: clean plugin
	mkdir -p ./out/strategy
	javac -d ./out/strategy -sourcepath ./src ./src/Runner.java
	jar cf ./out/strategy.jar -C ./out/strategy .

plugin:
	mkdir -p ./out/plugin
	javac -d ./out/plugin -sourcepath ./src ./src/LocalTestRendererListener.java

run-simulator:
	java -Xms512m -Xmx2G -server -jar ./vendor/local-runner/local-runner.jar \
		./local-runner.properties

run-strategy:
	java -cp ./out/strategy.jar Runner

run-simulator-and-strategy:
	@./run-simulator-and-strategy.sh

clean:
	rm -r ./out
