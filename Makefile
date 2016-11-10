strategy:
	mkdir -p ./out/classes
	javac -sourcepath ./src -d ./out/classes ./src/Runner.java
	jar cf ./out/strategy.jar -C ./out/classes .

run-simulator:
	java -Xms512m -Xmx2G -server -jar ./vendor/local-runner/local-runner.jar \
		./local-runner.properties

run-strategy:
	java -cp ./out/strategy.jar Runner

run-simulator-and-strategy:
	@./run-simulator-and-strategy.sh

clean:
	rm -r ./out
