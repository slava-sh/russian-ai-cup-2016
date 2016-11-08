.PHONY: strategy
strategy:
	mkdir -p ./out/classes
	javac -sourcepath ./src -d ./out/classes ./src/Runner.java
	jar cf ./out/strategy.jar -C ./out/classes .

.PHONY: run-simulator
run-simulator:
	java -Xms512m -Xmx2G -server -jar ./vendor/local-runner/local-runner.jar \
		./local-runner.properties

.PHONY: run-strategy
run-strategy:
	java -cp ./out/strategy.jar Runner

.PHONY: run-simulator-and-strategy
run-simulator-and-strategy:
	@./run-simulator-and-strategy.sh

.PHONY: clean
clean:
	rm ./out
