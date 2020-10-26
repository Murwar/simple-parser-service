package com.wine.to.up.simple.parser.service.SimpleParser.DbHandler;

import com.wine.to.up.simple.parser.service.domain.entity.Brands;
import com.wine.to.up.simple.parser.service.domain.entity.Countries;
import com.wine.to.up.simple.parser.service.repository.CountriesRepository;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
public class CountriesService {
    private final CountriesRepository countriesRepository;

    public CountriesService(@NonNull CountriesRepository countriesRepository) {
        this.countriesRepository = countriesRepository;
    }

    protected Countries sin(@NonNull String country) {
        if (!countriesRepository.existsCountriesByCountryName(country)) {
            countriesRepository.save(new Countries(country));
            log.trace("New Brand was added to DB: " + country);
        }

        return countriesRepository.findCountryByCountryName(country);
    }

    protected Countries saveCountry(@NonNull String country) throws ExecutionException, InterruptedException {
        CompletableFuture<Countries> completableFuture = CompletableFuture.supplyAsync(() -> {
            if (!countriesRepository.existsCountriesByCountryName(country)) {
                countriesRepository.save(new Countries(country));
                log.trace("New Brand was added to DB: " + country);
            }

            return countriesRepository.findCountryByCountryName(country);
        });
        return completableFuture.get();
    }
}
