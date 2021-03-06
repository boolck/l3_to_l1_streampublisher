package com.boolck.dev.listener;

import com.boolck.dev.calc.OrderBookEngine;
import com.boolck.dev.event.L3Request;
import com.boolck.dev.excp.InputReadException;
import com.boolck.dev.excp.OrderProcessingException;
import com.boolck.dev.model.OrderBook;
import com.boolck.dev.util.OrderBookRequestFileUtil;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/*
    1. Loads L3 file  with prespecified batch size
    2. converts to unified L3Request Model
    3. sends to OrderBookEngine for processing for each batch
    4. last request is passed as single object to simulate streaming
    5. based on micro batch design where batch size can be reduced further < 10
 */
public class BufferedCSVListener implements SourceListener{
    //name of csv file having L3 incoming data
    private final String file;
    private final int batchSize;
    private final int limit;

    //default constructor creates streaming processor by setting batchSize as unity
    public BufferedCSVListener(String inputRequestFile) {
        this(inputRequestFile,1,Integer.MAX_VALUE);
    }

    public BufferedCSVListener(String inputRequestFile,int batchSize, int limit) {
        this.file = inputRequestFile;
        this.batchSize = batchSize;
        this.limit = limit;
    }

    //parses the csv and calls orderbook engine to process the requests
    @Override
    public void process(OrderBookEngine orderBookEngine) throws InputReadException, OrderProcessingException {
        processAsBufferReader(orderBookEngine,batchSize,limit);
    }

    private void processAsBufferReader(OrderBookEngine orderBookEngine,int batchSize,int limit) throws OrderProcessingException, InputReadException {
        try {
            try (Scanner scanner = new Scanner(Paths.get(file))) {
                List<String> linesInBatch = new ArrayList<>(batchSize);
                List<String> totalLinesRead = new LinkedList<>();
                boolean skipHeader=false;
                while (scanner.hasNextLine()) {
                    //ignore header
                    if(!skipHeader){
                        scanner.nextLine();
                        skipHeader=true;
                    }

                    //keep populating the batch until batchSize
                     if (linesInBatch.size() < batchSize) {
                        String thisLine = scanner.nextLine();
                        linesInBatch.add(thisLine);
                        totalLinesRead.add(thisLine);
                    }
                    //else process this batch
                    else {
                        processBatch(linesInBatch, orderBookEngine);
                        linesInBatch.clear();
                    }
                    //if lines read exceeds limit, flush the batch
                    if(totalLinesRead.size()>=limit){
                        processBatch(linesInBatch, orderBookEngine);
                        linesInBatch.clear();
                        return;
                    }
                }
                //any residual stream left to be processed
                if(!linesInBatch.isEmpty()){
                    processBatch(linesInBatch, orderBookEngine);
                    linesInBatch.clear();
                }
                orderBookEngine.processOutOfOrderMessages();
            }
        } catch (IOException e) {
            throw new InputReadException(e.getMessage(),e.getCause());
        }
    }

    /*
    this method converts each l3 csv line to correct L3 request (new/update/cancel/trade)
    then passes to order processing engine for BBO processing
     last request is always passed as single request to simulate streaming.
     */

    private void processBatch(List<String> lines, OrderBookEngine orderBookEngine) throws OrderProcessingException {
        if(lines.isEmpty()){
            return;
        }

        List<L3Request> lastButOneRequest =
                IntStream.range(0, lines.size() - 1)
                        .mapToObj(i -> OrderBookRequestFileUtil.parseOrderBookRow(lines.get(i))).
                        map(OrderBook::getL3Request)
                        .sorted(Comparator.comparing(r -> r.getOrder().getSeqNumAsInt()))
                        .collect(Collectors.toCollection(ArrayList::new));

        orderBookEngine.processRequest(lastButOneRequest.stream());

        L3Request lastRequest= OrderBookRequestFileUtil.parseOrderBookRow(lines.get(lines.size()-1)).getL3Request();
        orderBookEngine.checkSequenceAndProcessBuffer(lastRequest);
    }

}
