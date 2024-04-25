package com.salesmanager.shop.store.facade.catalog;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.catalog.catalog.CatalogEntryService;
import com.salesmanager.core.business.services.catalog.catalog.CatalogService;
import com.salesmanager.core.model.catalog.catalog.Catalog;
import com.salesmanager.core.model.catalog.catalog.CatalogCategoryEntry;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.mapper.Mapper;
import com.salesmanager.shop.mapper.catalog.PersistableCatalogMapper;
import com.salesmanager.shop.mapper.catalog.ReadableCatalogCategoryEntryMapper;
import com.salesmanager.shop.mapper.catalog.ReadableCatalogMapper;
import com.salesmanager.shop.model.catalog.catalog.PersistableCatalog;
import com.salesmanager.shop.model.catalog.catalog.PersistableCatalogCategoryEntry;
import com.salesmanager.shop.model.catalog.catalog.ReadableCatalog;
import com.salesmanager.shop.model.catalog.catalog.ReadableCatalogCategoryEntry;
import com.salesmanager.shop.model.entity.ReadableEntityList;
import com.salesmanager.shop.store.api.exception.OperationNotAllowedException;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.store.api.exception.ServiceRuntimeException;
import org.jsoup.helper.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service("catalogFacade")
public class CatalogFacadeImpl implements CatalogFacade {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private CatalogEntryService catalogEntryService;

    @Autowired
    private PersistableCatalogMapper persistableCatalogMapper;

    @Autowired
    private ReadableCatalogMapper readableCatalogMapper;

    @Autowired
    private Mapper<PersistableCatalogCategoryEntry, CatalogCategoryEntry> persistableCatalogEntryMapper;

    @Autowired
    private ReadableCatalogCategoryEntryMapper readableCatalogEntryMapper;

    @Override
    public ReadableCatalog saveCatalog(PersistableCatalog catalog, MerchantStore store, Language language) {
        validateCatalogStoreAndLanguage(catalog, store, language);

        Catalog catalogToSave = persistableCatalogMapper.convert(catalog, store, language);
        if (catalogService.existByCode(catalog.getCode(), store)) {
            throw new OperationNotAllowedException("Catalog [" + catalog.getCode() + "] already exists");
        }
        catalogService.saveOrUpdate(catalogToSave, store);
        return readableCatalogMapper.convert(catalogService.getByCode(catalogToSave.getCode(), store).get(), store, language);
    }

    @Override
    public void deleteCatalog(Long catalogId, MerchantStore store, Language language) {
        validateStoreAndLanguage(store, language);
        Catalog c = validateAndGetCatalogById(catalogId, store);
        try {
            catalogService.delete(c);
        } catch (ServiceException e) {
            throw new ServiceRuntimeException("Error while deleting catalog id [" + catalogId + "]", e);
        }
    }

    @Override
    public ReadableCatalog getCatalog(String code, MerchantStore store, Language language) {
        validateStoreAndLanguage(store, language);
        return readableCatalogMapper.convert(validateAndGetCatalogByCode(code, store), store, language);
    }

    @Override
    public void updateCatalog(Long catalogId, PersistableCatalog catalog, MerchantStore store, Language language) {
        validateCatalogStoreAndLanguage(catalog, store, language);
        Catalog c = validateAndGetCatalogById(catalogId, store);

        c.setDefaultCatalog(catalog.isDefaultCatalog());
        c.setVisible(catalog.isVisible());
        catalogService.saveOrUpdate(c, store);
    }

    @Override
    public ReadableCatalog getCatalog(Long id, MerchantStore store, Language language) {
        validateStoreAndLanguage(store, language);
        return readableCatalogMapper.convert(validateAndGetCatalogById(id, store), store, language);
    }

    @Override
    public ReadableEntityList<ReadableCatalog> getListCatalogs(Optional<String> code, MerchantStore store, Language language, int page, int count) {
        validateStoreAndLanguage(store, language);

        String catalogCode = code.orElse(null);
        Page<Catalog> catalogs = catalogService.getCatalogs(store, language, catalogCode, page, count);
        if (catalogs.isEmpty()) {
            return new ReadableEntityList<>();
        }

        List<ReadableCatalog> readableList = catalogs.getContent().stream()
                .map(cat -> readableCatalogMapper.convert(cat, store, language))
                .collect(Collectors.toList());
        return createReadableList(catalogs, readableList);
    }

    @Override
    public ReadableCatalogCategoryEntry addCatalogEntry(PersistableCatalogCategoryEntry entry, MerchantStore store, Language language) {
        validateStoreAndLanguage(store, language);
        Validate.notNull(entry, "PersistableCatalogEntry cannot be null");
        Validate.notNull(entry.getCatalog(), "CatalogEntry.catalog cannot be null");

        Catalog catalog = validateAndGetCatalogByCode(entry.getCatalog(), store);
        CatalogCategoryEntry catalogEntryModel = persistableCatalogEntryMapper.convert(entry, store, language);
        catalogEntryService.add(catalogEntryModel, catalog);
        return readableCatalogEntryMapper.convert(catalogEntryModel, store, language);
    }

    // Helper methods
    private void validateCatalogStoreAndLanguage(Object catalog, MerchantStore store, Language language) {
        Validate.notNull(catalog, "Catalog object cannot be null");
        validateStoreAndLanguage(store, language);
    }

    private void validateStoreAndLanguage(MerchantStore store, Language language) {
        Validate.notNull(store, "MerchantStore cannot be null");
        Validate.notNull(language, "Language cannot be null");
    }

    private Catalog validateAndGetCatalogById(Long catalogId, MerchantStore store) {
        return catalogService.getById(catalogId).filter(c -> store.equals(c.getMerchantStore()))
                .orElseThrow(() -> new ResourceNotFoundException("Catalog with id [" + catalogId + "] not found or does not belong to the specified store"));
    }

    private Catalog validateAndGetCatalogByCode(String code, MerchantStore store) {
        return catalogService.getByCode(code, store)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog with code [" + code + "] not found"));
    }
}
