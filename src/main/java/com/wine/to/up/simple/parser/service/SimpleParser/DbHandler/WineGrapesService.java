package com.wine.to.up.simple.parser.service.SimpleParser.DbHandler;

import com.wine.to.up.simple.parser.service.domain.entity.Grapes;
import com.wine.to.up.simple.parser.service.domain.entity.Wine;
import com.wine.to.up.simple.parser.service.domain.entity.WineGrapes;
import com.wine.to.up.simple.parser.service.repository.WineGrapesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WineGrapesService {
    private final WineGrapesRepository wineGrapesRepository;

    public WineGrapesService(WineGrapesRepository wineGrapesRepository) {
        this.wineGrapesRepository = wineGrapesRepository;
    }

    protected void saveWineGrapes(Grapes grapeEntity, Wine wineEntity) {
        if (!wineGrapesRepository.existsWineGrapesByGrapeIdAndAndWineId(grapeEntity, wineEntity)) {
            wineGrapesRepository.save(new WineGrapes(wineEntity, grapeEntity));
            log.trace("New Connection between Wine and Grape was added to DB");
        }
    }
}
