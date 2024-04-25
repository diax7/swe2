package com.salesmanager.shop.store.api.v1.order;

import java.security.Principal;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.salesmanager.core.business.services.catalog.pricing.PricingService;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.reference.country.CountryService;
import com.salesmanager.core.model.common.Delivery;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.shipping.ShippingOption;
import com.salesmanager.core.model.shipping.ShippingQuote;
import com.salesmanager.core.model.shipping.ShippingSummary;
import com.salesmanager.core.model.shoppingcart.ShoppingCart;
import com.salesmanager.shop.model.customer.address.AddressLocation;
import com.salesmanager.shop.model.order.shipping.ReadableShippingSummary;
import com.salesmanager.shop.populator.order.ReadableShippingSummaryPopulator;
import com.salesmanager.shop.store.controller.order.facade.OrderFacade;
import com.salesmanager.shop.store.controller.shoppingCart.facade.ShoppingCartFacade;
import com.salesmanager.shop.utils.LabelUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import springfox.documentation.annotations.ApiIgnore;

@Controller
@RequestMapping("/api/v1")
@Api(tags = {"Shipping Quotes and Calculation resource (Shipping Api)"})
public class OrderShippingApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderShippingApi.class);

  @Inject private CustomerService customerService;
  @Inject private OrderFacade orderFacade;
  @Inject private ShoppingCartFacade shoppingCartFacade;
  @Inject private LabelUtils messages;
  @Inject private PricingService pricingService;
  @Inject private CountryService countryService;

  @RequestMapping(value = "/auth/cart/{code}/shipping", method = RequestMethod.GET)
  @ResponseBody
  public ReadableShippingSummary shipping(
      @PathVariable final String code,
      @ApiIgnore MerchantStore merchantStore,
      @ApiIgnore Language language,
      HttpServletRequest request,
      HttpServletResponse response) {
    try {
      ShoppingCart cart = validateAndGetCart(code, merchantStore, request, response);
      if (cart == null) return null;

      Customer customer = validateAndGetCustomer(request, response);
      if (customer == null || !validateCustomerCart(cart, customer, response)) return null;

      return calculateShippingSummary(customer, cart, merchantStore, language, request.getLocale());
    } catch (Exception e) {
      LOGGER.error("Error while getting shipping quote", e);
      sendError(response, 503, "Error while getting shipping quote" + e.getMessage());
      return null;
    }
  }

  @RequestMapping(value = "/cart/{code}/shipping", method = RequestMethod.POST)
  @ResponseBody
  public ReadableShippingSummary shipping(
      @PathVariable final String code,
      @RequestBody AddressLocation address,
      @ApiIgnore MerchantStore merchantStore,
      @ApiIgnore Language language,
      HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    try {
      ShoppingCart cart = validateAndGetCart(code, merchantStore, request, response);
      if (cart == null) return null;

      Customer temp = createTemporaryCustomer(address, merchantStore);
      return calculateShippingSummary(temp, cart, merchantStore, language, request.getLocale());
    } catch (Exception e) {
      LOGGER.error("Error while getting shipping quote", e);
      sendError(response, 503, "Error while getting shipping quote" + e.getMessage());
      return null;
    }
  }

  private ShoppingCart validateAndGetCart(String code, MerchantStore merchantStore, HttpServletRequest request, HttpServletResponse response) {
    ShoppingCart cart = shoppingCartFacade.getShoppingCartModel(code, merchantStore);
    if (cart == null) {
      sendError(response, 404, "Cart code " + code + " does not exist");
    }
    return cart;
  }

  private Customer validateAndGetCustomer(HttpServletRequest request, HttpServletResponse response) {
    Principal principal = request.getUserPrincipal();
    String userName = principal != null ? principal.getName() : null;
    if (userName == null) {
      sendError(response, 503, "User not logged in");
      return null;
    }
    Customer customer = customerService.getByNick(userName);
    if (customer == null) {
      sendError(response, 503, "Error while getting user details to calculate shipping quote");
    }
    return customer;
  }

  private boolean validateCustomerCart(ShoppingCart cart, Customer customer, HttpServletResponse response) {
    if (cart.getCustomerId() == null || cart.getCustomerId().longValue() != customer.getId().longValue()) {
      sendError(response, 404, "Cart does not exist for user " + customer.getNick());
      return false;
    }
    return true;
  }

  private Customer createTemporaryCustomer(AddressLocation address, MerchantStore merchantStore) {
    Delivery addr = new Delivery();
    addr.setPostalCode(address.getPostalCode());
    Country c = countryService.getByCode(address.getCountryCode());
    addr.setCountry(c != null ? c : merchantStore.getCountry());
    Customer temp = new Customer();
    temp.setAnonymous(true);
    temp.setDelivery(addr);
    return temp;
  }

  private ReadableShippingSummary calculateShippingSummary(Customer customer, ShoppingCart cart, MerchantStore merchantStore, Language language, Locale locale) {
    ShippingQuote quote = orderFacade.getShippingQuote(customer, cart, merchantStore, language);
    ShippingSummary summary = orderFacade.getShippingSummary(quote, merchantStore, language);
    ReadableShippingSummary shippingSummary = new ReadableShippingSummary();
    ReadableShippingSummaryPopulator populator = new ReadableShippingSummaryPopulator();
    populator.setPricingService(pricingService);
    populator.populate(summary, shippingSummary, merchantStore, language);
    populateShippingOptions(quote, shippingSummary, merchantStore, locale);
    return shippingSummary;
  }

  private void populateShippingOptions(ShippingQuote quote, ReadableShippingSummary shippingSummary, MerchantStore merchantStore, Locale locale) {
    List<ShippingOption> options = quote.getShippingOptions();
    if (!CollectionUtils.isEmpty(options)) {
      for (ShippingOption shipOption : options) {
        StringBuilder moduleName = new StringBuilder("module.shipping.").append(shipOption.getShippingModuleCode());
        String carrier = messages.getMessage(moduleName.toString(), new String[] {merchantStore.getStorename()}, locale);
        shipOption.setDescription(carrier);
        String note = messages.getMessage(moduleName.append(".note").toString(), locale, "");
        shipOption.setNote(note);
        setOptionName(shipOption, moduleName, locale);
      }
      shippingSummary.setShippingOptions(options);
    }
  }

  private void setOptionName(ShippingOption shipOption, StringBuilder moduleName, Locale locale) {
    if (!StringUtils.isBlank(shipOption.getOptionCode())) {
      try {
        String optionName = messages.getMessage(moduleName.toString(), locale);
        shipOption.setOptionName(optionName);
      } catch (Exception e) { // label not found
        LOGGER.warn("No shipping code found for " + moduleName.toString());
      }
    }
  }

  private void sendError(HttpServletResponse response, int errorCode, String errorMessage) {
    try {
      response.sendError(errorCode, errorMessage);
    } catch (Exception ignore) {}
  }
}
