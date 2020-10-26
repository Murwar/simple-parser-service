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

    private static String URL;
    private static final int PAGES_TO_PARSE = 100; // currently max 107, lower const value for testing purposes
    private static UpdateProducts.UpdateProductsMessage messageToKafka;
    private static String HOME_URL;
    private static String WINE_URL;

    private final ExecutorService pagesExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService winesExecutor = Executors.newFixedThreadPool(15);

    @Value("${parser.url}")
    public void setURLStatic(String URL_FROM_PROPERTY) {
        URL = URL_FROM_PROPERTY;
        HOME_URL = URL + "/catalog/vino/";
        WINE_URL = URL + "/catalog/vino/page";
    }

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

    public void startParser() {
        long start = System.currentTimeMillis();

        List<CompletableFuture<?>> futures = new ArrayList<>();
        ArrayBlockingQueue<String> wineURLs = new ArrayBlockingQueue<>(100_000);

        WineService dbHandler = new WineService(grapesRepository, brandsRepository, countriesRepository,
                wineGrapesRepository, wineRepository);
        WineToDTO wineToDTO = new WineToDTO();
        List<UpdateProducts.Product> products = new ArrayList<>();
        UpdateProducts.UpdateProductsMessage message;

        AtomicLong pageCounter = new AtomicLong(1);
        for (int i = 0; i < 15; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                Document doc;
                try {
                    while (pageCounter.longValue() <= PAGES_TO_PARSE) {
                        doc = Jsoup.connect(WINE_URL + pageCounter.get()).get();
                        Elements wines = doc.getElementsByClass("catalog-grid__item");
                        for (Element wine : wines) {
                            if (!wineURLs.contains(wine.getElementsByClass("product-snippet__name").attr("href")))
                                wineURLs.add(wine.getElementsByClass("product-snippet__name").attr("href"));
                        }
                        log.debug("Parsed {} wines from url {}", wines.size(),
                                WINE_URL + pageCounter.getAndIncrement());
                    }
                } catch (IOException e) {
                    log.error("Error while parsing page: ", e);
                }
            }, pagesExecutor);
            futures.add(future);
        }

        AtomicInteger wineCounter = new AtomicInteger(1);
        for (int i = 0; i < 50; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    while (true) {
                        String wineURL = wineURLs.poll(10, TimeUnit.SECONDS);
                        if (wineURL == null) {
                            return;
                        }
                        try {
                            SimpleWine wine = Parser.parseWine(Parser.URLToDocument(URL + wineURL));
                            UpdateProducts.Product newProduct = wineToDTO.getProtoWine(wine);
                            if (!products.contains(newProduct)) {
                                products.add(newProduct);
                            }
                            try {
                                dbHandler.saveAllWineParsedInfo(wine);
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
                }

            }, winesExecutor);
            futures.add(future);
        }
        log.debug("Total {} wines", wineURLs.size());

        futures.forEach(CompletableFuture::join);

        log.info("End of adding information to the database.");

        if (products.size() == 0) {
            log.error("\t Z E R O\tP A R S I N G");
        } else {
            message = UpdateProducts.UpdateProductsMessage.newBuilder().addAllProducts(products).build();
            kafkaSendMessageService.sendMessage(message);
            log.info("TIME : {} min {} seconds", TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - start), TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - start));
            log.info("End of parsing, {} wines collected and sent to Kafka", products.size());
            setMessage(message);
        }
    }

    public void setMessage(UpdateProducts.UpdateProductsMessage message) {
        messageToKafka = message;
    }

    public UpdateProducts.UpdateProductsMessage getMessage() {
        return messageToKafka;
    }
}