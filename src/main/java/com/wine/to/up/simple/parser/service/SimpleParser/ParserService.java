package com.wine.to.up.simple.parser.service.SimpleParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.wine.to.up.commonlib.messaging.KafkaMessageSender;
import com.wine.to.up.parser.common.api.schema.UpdateProducts;
import com.wine.to.up.simple.parser.service.SimpleParser.DbHandler.WineService;
import com.wine.to.up.simple.parser.service.repository.*;
import org.jsoup.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ParserService {
    @Value("${parser.url}")
    private String url;
    @Value("${parser.wineurl}")
    private String wineUrl;
    private static final int PAGES_TO_PARSE = 106; // currently max 106, lower const value for testing purposes
    private static final int NUMBER_OF_THREADS = 15;
    private UpdateProducts.UpdateProductsMessage messageToKafka;
    private final ExecutorService pagesExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService winesExecutor = Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    @Autowired
    KafkaMessageSender<UpdateProducts.UpdateProductsMessage> kafkaSendMessageService;
    @Autowired
    private GrapesRepository grapesRepository;
    @Autowired
    private BrandsRepository brandsRepository;
    @Autowired
    private CountriesRepository countriesRepository;
    @Autowired
    private WineGrapesRepository wineGrapesRepository;
    @Autowired
    private WineRepository wineRepository;

    /**
     * @param someURL URL to get jsoup Document
     * @return Jsoup Document class
     * @throws IOException IDK
     */

    public static Document urlToDocument(String someURL) throws IOException {
        Document wineDoc = Jsoup.connect(someURL).get();
        while (!(wineDoc.getElementsByClass("product-page").first().children().first().className().equals("product"))) {
            log.error("Doing re-request...");
            wineDoc = Jsoup.connect(someURL).get();
        }
        return wineDoc;
    }

    /**
     * Multithreading simplewine parser
     */
    public void startParser() {
        long start = System.currentTimeMillis();

        List<CompletableFuture<?>> futures = new ArrayList<>();
        ArrayBlockingQueue<String> wineURLs = new ArrayBlockingQueue<>(100_000);

        WineService dbHandler = new WineService(grapesRepository, brandsRepository, countriesRepository,
                wineGrapesRepository, wineRepository);
        List<UpdateProducts.Product> products = new ArrayList<>();

        AtomicLong pageCounter = new AtomicLong(1);
        for (int i = 0; i < NUMBER_OF_THREADS; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                Document doc;
                try {
                    while (pageCounter.longValue() <= PAGES_TO_PARSE) {
                        doc = Jsoup.connect(wineUrl + pageCounter.get()).get();
                        Elements wines = doc.getElementsByClass("catalog-grid__item");
                        for (Element wine : wines) {
                            if (!wineURLs.contains(wine.getElementsByClass("product-snippet__name").attr("href")))
                                wineURLs.add(wine.getElementsByClass("product-snippet__name").attr("href"));
                        }
                        log.debug("Parsed {} wines from url {}", wines.size(),
                                wineUrl + pageCounter.getAndIncrement());
                    }
                } catch (IOException e) {
                    log.error("Error while parsing page: ", e);
                }
            }, pagesExecutor);
            futures.add(future);
        }

        AtomicInteger wineCounter = new AtomicInteger(1);
        for (int i = 0; i < NUMBER_OF_THREADS; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    while (true) {
                        String wineURL = wineURLs.poll(10, TimeUnit.SECONDS);
                        if (wineURL == null) {
                            return;
                        }
                        try {
                            SimpleWine wine = Parser.parseWine(urlToDocument(url + wineURL));
                            UpdateProducts.Product newProduct = WineToDTO.getProtoWine(wine);
                            if (!products.contains(newProduct)) {
                                products.add(newProduct);
                            }

                            try {
                                if ((wine.getBrandID() != null) && (wine.getCountryID() != null)
                                        && !(wine.getBrandID().equals(""))) {
                                    Thread.sleep((long) (Math.random() * 1500));
                                    dbHandler.saveAllWineParsedInfo(wine);
                                }
                            } catch (Exception e) {
                                log.error("DB error ", e);
                            }

                            log.trace("Wine: {} added to database", wineCounter.getAndIncrement());
                        } catch (IOException e) {
                            log.error("Error while parsing page: ", e);
                        }
                    }
                } catch (InterruptedException e) {
                    log.error("Interrupt ", e);
                    Thread.currentThread().interrupt();
                }
            }, winesExecutor);
            futures.add(future);
        }
        futures.forEach(CompletableFuture::join);
        log.info("End of adding information to the database.");
        if (products.isEmpty()) {
            log.error("\t Z E R O\tP A R S I N G");
        } else {
            UpdateProducts.UpdateProductsMessage message;
            if (products.size() >= 1000) {
                int messageSize = (int) Math.round(products.size() / 4.0);
                for (int i = 0; i < 4; i++) {
                    if (i == 3)
                        message = UpdateProducts.UpdateProductsMessage.newBuilder()
                                .addAllProducts(products.subList(i * messageSize, products.size() - 1)).build();
                    else
                        message = UpdateProducts.UpdateProductsMessage.newBuilder()
                                .addAllProducts(products.subList(i * messageSize, (i + 1) * messageSize - 1)).build();
                    kafkaSendMessageService.sendMessage(message);
                }
            } else {
                message = UpdateProducts.UpdateProductsMessage.newBuilder().addAllProducts(products).build();
                kafkaSendMessageService.sendMessage(message);
            }

            log.info("TIME : {} min {} seconds", TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - start),
                    TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()
                            - TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - start) * 60000 - start));
            log.info("End of parsing, {} wines collected and sent to Kafka", products.size());

            messageToKafka = UpdateProducts.UpdateProductsMessage.newBuilder().addAllProducts(products).build();
        }
    }

    /**
     * Getting of all products
     *
     * @return Message to Kafka
     */
    public UpdateProducts.UpdateProductsMessage getMessage() {
        return messageToKafka;
    }
}