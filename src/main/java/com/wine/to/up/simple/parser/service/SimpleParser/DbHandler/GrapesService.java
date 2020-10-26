package com.wine.to.up.simple.parser.service.SimpleParser.DbHandler;

import com.wine.to.up.simple.parser.service.domain.entity.Grapes;
import com.wine.to.up.simple.parser.service.repository.GrapesRepository;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
public class GrapesService {
    private final GrapesRepository grapesRepository;

    public GrapesService(@NonNull GrapesRepository grapesRepository) {
        this.grapesRepository = grapesRepository;
    }

    protected Grapes sin(String grape) {
        if (!grapesRepository.existsGrapesByGrapeName(grape)) {
            grapesRepository.save(new Grapes(grape));
            log.trace("New Brand was added to DB: " + grape);
        }

        return grapesRepository.findGrapeByGrapeName(grape);
    }

    protected Grapes saveGrape(String grape) throws ExecutionException, InterruptedException {
        CompletableFuture<Grapes> completableFuture = CompletableFuture.supplyAsync(() -> {
            if (!grapesRepository.existsGrapesByGrapeName(grape)) {
                grapesRepository.save(new Grapes(grape));
                log.trace("New Brand was added to DB: " + grape);
            }

            return grapesRepository.findGrapeByGrapeName(grape);
        });
        return completableFuture.get();
    }
}
