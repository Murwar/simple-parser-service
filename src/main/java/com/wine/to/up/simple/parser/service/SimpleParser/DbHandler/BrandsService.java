package com.wine.to.up.simple.parser.service.SimpleParser.DbHandler;

import com.wine.to.up.simple.parser.service.domain.entity.Brands;
import com.wine.to.up.simple.parser.service.repository.BrandsRepository;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
public class BrandsService {
    private final BrandsRepository brandsRepository;

    public BrandsService(@NonNull BrandsRepository brandsRepository) {
        this.brandsRepository = brandsRepository;
    }

    public Brands sin(@NonNull String brand) {
        if (!brandsRepository.existsBrandsByBrandName(brand)) {
            brandsRepository.save(new Brands(brand));
            log.trace("New Brand was added to DB: " + brand);
        }

        return brandsRepository.findBrandByBrandName(brand);
    }

    public Brands saveBrand(@NonNull String brand) throws ExecutionException, InterruptedException {
        CompletableFuture<Brands> completableFuture = CompletableFuture.supplyAsync(() -> {
            if (!brandsRepository.existsBrandsByBrandName(brand)) {
                brandsRepository.save(new Brands(brand));
                log.trace("New Brand was added to DB: " + brand);
            }

            return brandsRepository.findBrandByBrandName(brand);
        });
        return completableFuture.get();
    }
}
