/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.controller.form.UpdateTicketOwnerForm;
import alfio.manager.support.CategoryEvaluator;
import alfio.manager.support.FeeCalculator;
import alfio.manager.support.PartialTicketTextGenerator;
import alfio.manager.support.PaymentResult;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.system.Mailer;
import alfio.model.*;
import alfio.model.AdditionalServiceItem.AdditionalServiceItemStatus;
import alfio.model.PromoCodeDiscount.DiscountType;
import alfio.model.SpecialPrice.Status;
import alfio.model.Ticket.TicketStatus;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.decorator.AdditionalServiceItemPriceContainer;
import alfio.model.decorator.AdditionalServicePriceContainer;
import alfio.model.decorator.TicketPriceContainer;
import alfio.model.group.LinkedGroup;
import alfio.model.modification.ASReservationWithOptionalCodeModification;
import alfio.model.modification.AdditionalServiceReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.PaymentContext;
import alfio.model.transaction.PaymentProxy;
import alfio.model.user.Organization;
import alfio.model.user.Role;
import alfio.repository.*;
import alfio.repository.user.OrganizationRepository;
import alfio.repository.user.UserRepository;
import alfio.util.*;
import ch.digitalfondue.npjt.AffectedRowCountAndKey;
import de.danielbechler.diff.ObjectDifferBuilder;
import de.danielbechler.diff.node.DiffNode;
import de.danielbechler.diff.node.Visit;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.context.MessageSource;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static alfio.model.Audit.EntityType.RESERVATION;
import static alfio.model.BillingDocument.Type.*;
import static alfio.model.PromoCodeDiscount.categoriesOrNull;
import static alfio.model.TicketReservation.TicketReservationStatus.*;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.util.MonetaryUtil.formatCents;
import static alfio.util.MonetaryUtil.unitToCents;
import static alfio.util.OptionalWrapper.optionally;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.*;
import static org.apache.commons.lang3.StringUtils.*;
import static org.apache.commons.lang3.time.DateUtils.addHours;
import static org.apache.commons.lang3.time.DateUtils.truncate;

@Component
@Transactional
@Log4j2
public class TicketReservationManager {
    
    private static final String STUCK_TICKETS_MSG = "there are stuck tickets for the event %s. Please check admin area.";
    private static final String STUCK_TICKETS_SUBJECT = "warning: stuck tickets found";
    static final String NOT_YET_PAID_TRANSACTION_ID = "not-paid";

    private final EventRepository eventRepository;
    private final OrganizationRepository organizationRepository;
    private final TicketRepository ticketRepository;
    private final TicketReservationRepository ticketReservationRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository;
    private final ConfigurationManager configurationManager;
    private final PaymentManager paymentManager;
    private final PromoCodeDiscountRepository promoCodeDiscountRepository;
    private final SpecialPriceRepository specialPriceRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationManager notificationManager;
    private final MessageSource messageSource;
    private final TemplateManager templateManager;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final TransactionTemplate serializedTransactionTemplate;
    private final TransactionTemplate nestedTransactionTemplate;
    private final WaitingQueueManager waitingQueueManager;
    private final TicketFieldRepository ticketFieldRepository;
    private final AdditionalServiceRepository additionalServiceRepository;
    private final AdditionalServiceItemRepository additionalServiceItemRepository;
    private final AdditionalServiceTextRepository additionalServiceTextRepository;
    private final InvoiceSequencesRepository invoiceSequencesRepository;
    private final AuditingRepository auditingRepository;
    private final UserRepository userRepository;
    private final ExtensionManager extensionManager;
    private final TicketSearchRepository ticketSearchRepository;
    private final GroupManager groupManager;
    private final BillingDocumentRepository billingDocumentRepository;

    public static class NotEnoughTicketsException extends RuntimeException {

    }

    public static class MissingSpecialPriceTokenException extends RuntimeException {
    }

    public static class InvalidSpecialPriceTokenException extends RuntimeException {

    }

    public static class TooManyTicketsForDiscountCodeException extends RuntimeException {
    }

    public TicketReservationManager(EventRepository eventRepository,
                                    OrganizationRepository organizationRepository,
                                    TicketRepository ticketRepository,
                                    TicketReservationRepository ticketReservationRepository,
                                    TicketCategoryRepository ticketCategoryRepository,
                                    TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository,
                                    ConfigurationManager configurationManager,
                                    PaymentManager paymentManager,
                                    PromoCodeDiscountRepository promoCodeDiscountRepository,
                                    SpecialPriceRepository specialPriceRepository,
                                    TransactionRepository transactionRepository,
                                    NotificationManager notificationManager,
                                    MessageSource messageSource,
                                    TemplateManager templateManager,
                                    PlatformTransactionManager transactionManager,
                                    WaitingQueueManager waitingQueueManager,
                                    TicketFieldRepository ticketFieldRepository,
                                    AdditionalServiceRepository additionalServiceRepository,
                                    AdditionalServiceItemRepository additionalServiceItemRepository,
                                    AdditionalServiceTextRepository additionalServiceTextRepository,
                                    InvoiceSequencesRepository invoiceSequencesRepository,
                                    AuditingRepository auditingRepository,
                                    UserRepository userRepository,
                                    ExtensionManager extensionManager, TicketSearchRepository ticketSearchRepository,
                                    GroupManager groupManager,
                                    BillingDocumentRepository billingDocumentRepository) {
        this.eventRepository = eventRepository;
        this.organizationRepository = organizationRepository;
        this.ticketRepository = ticketRepository;
        this.ticketReservationRepository = ticketReservationRepository;
        this.ticketCategoryRepository = ticketCategoryRepository;
        this.ticketCategoryDescriptionRepository = ticketCategoryDescriptionRepository;
        this.configurationManager = configurationManager;
        this.paymentManager = paymentManager;
        this.promoCodeDiscountRepository = promoCodeDiscountRepository;
        this.specialPriceRepository = specialPriceRepository;
        this.transactionRepository = transactionRepository;
        this.notificationManager = notificationManager;
        this.messageSource = messageSource;
        this.templateManager = templateManager;
        this.waitingQueueManager = waitingQueueManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager, new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        DefaultTransactionDefinition serialized = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        serialized.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.serializedTransactionTemplate = new TransactionTemplate(transactionManager, serialized);
        this.nestedTransactionTemplate = new TransactionTemplate(transactionManager, new DefaultTransactionDefinition((TransactionDefinition.PROPAGATION_NESTED)));
        this.ticketFieldRepository = ticketFieldRepository;
        this.additionalServiceRepository = additionalServiceRepository;
        this.additionalServiceItemRepository = additionalServiceItemRepository;
        this.additionalServiceTextRepository = additionalServiceTextRepository;
        this.invoiceSequencesRepository = invoiceSequencesRepository;
        this.auditingRepository = auditingRepository;
        this.userRepository = userRepository;
        this.extensionManager = extensionManager;
        this.ticketSearchRepository = ticketSearchRepository;
        this.groupManager = groupManager;
        this.billingDocumentRepository = billingDocumentRepository;
    }
    
    /**
     * Create a ticket reservation. It will create a reservation _only_ if it can find enough tickets. Note that it will not do date/validity validation. This must be ensured by the
     * caller.
     *
     * @param event
     * @param list
     * @param reservationExpiration
     * @param forWaitingQueue
     * @return
     */
    public String createTicketReservation(Event event,
                                          List<TicketReservationWithOptionalCodeModification> list,
                                          List<ASReservationWithOptionalCodeModification> additionalServices,
                                          Date reservationExpiration,
                                          Optional<String> specialPriceSessionId,
                                          Optional<String> promotionCodeDiscount,
                                          Locale locale,
                                          boolean forWaitingQueue) throws NotEnoughTicketsException, MissingSpecialPriceTokenException, InvalidSpecialPriceTokenException {
        String reservationId = UUID.randomUUID().toString();
        
        Optional<PromoCodeDiscount> discount = promotionCodeDiscount.flatMap((promoCodeDiscount) -> promoCodeDiscountRepository.findPromoCodeInEventOrOrganization(event.getId(), promoCodeDiscount));
        
        ticketReservationRepository.createNewReservation(reservationId, ZonedDateTime.now(event.getZoneId()), reservationExpiration, discount.map(PromoCodeDiscount::getId).orElse(null), locale.getLanguage(), event.getId(), event.getVat(), event.isVatIncluded());
        list.forEach(t -> reserveTicketsForCategory(event, specialPriceSessionId, reservationId, t, locale, forWaitingQueue, discount.orElse(null)));

        int ticketCount = list
            .stream()
            .map(TicketReservationWithOptionalCodeModification::getAmount)
            .mapToInt(Integer::intValue).sum();

        // apply valid additional service with supplement policy mandatory one for ticket
        additionalServiceRepository.findAllInEventWithPolicy(event.getId(), AdditionalService.SupplementPolicy.MANDATORY_ONE_FOR_TICKET)
            .stream()
            .filter(AdditionalService::getSaleable)
            .forEach(as -> {
                AdditionalServiceReservationModification asrm = new AdditionalServiceReservationModification();
                asrm.setAdditionalServiceId(as.getId());
                asrm.setQuantity(ticketCount);
                reserveAdditionalServicesForReservation(event.getId(), reservationId, new ASReservationWithOptionalCodeModification(asrm, Optional.empty()), discount.orElse(null));
        });

        additionalServices.forEach(as -> reserveAdditionalServicesForReservation(event.getId(), reservationId, as, discount.orElse(null)));
        auditingRepository.insert(reservationId, null, event.getId(), Audit.EventType.RESERVATION_CREATE, new Date(), Audit.EntityType.RESERVATION, reservationId);
        if(isDiscountCodeUsageExceeded(reservationId)) {
            throw new TooManyTicketsForDiscountCodeException();
        }
        return reservationId;
    }

    public Pair<List<TicketReservation>, Integer> findAllReservationsInEvent(int eventId, Integer page, String search, List<TicketReservationStatus> status) {
        final int pageSize = 50;
        int offset = page == null ? 0 : page * pageSize;
        String toSearch = StringUtils.trimToNull(search);
        toSearch = toSearch == null ? null : ("%" + toSearch + "%");
        List<String> toFilter = (status == null || status.isEmpty() ? Arrays.asList(TicketReservationStatus.values()) : status).stream().map(TicketReservationStatus::toString).collect(toList());
        List<TicketReservation> reservationsForEvent = ticketSearchRepository.findReservationsForEvent(eventId, offset, pageSize, toSearch, toFilter);
        return Pair.of(reservationsForEvent, ticketSearchRepository.countReservationsForEvent(eventId, toSearch, toFilter));
    }

    void reserveTicketsForCategory(Event event, Optional<String> specialPriceSessionId, String transactionId, TicketReservationWithOptionalCodeModification ticketReservation, Locale locale, boolean forWaitingQueue, PromoCodeDiscount discount) {
        //first check if there is another pending special price token bound to the current sessionId
        Optional<SpecialPrice> specialPrice = fixToken(ticketReservation.getSpecialPrice(), ticketReservation.getTicketCategoryId(), event.getId(), specialPriceSessionId, ticketReservation);

        List<Integer> reservedForUpdate = reserveTickets(event.getId(), ticketReservation, forWaitingQueue ? asList(TicketStatus.RELEASED, TicketStatus.PRE_RESERVED) : singletonList(TicketStatus.FREE));
        int requested = ticketReservation.getAmount();
        if (reservedForUpdate.size() != requested) {
            throw new NotEnoughTicketsException();
        }

        TicketCategory category = ticketCategoryRepository.getByIdAndActive(ticketReservation.getTicketCategoryId(), event.getId());
        if (specialPrice.isPresent()) {
            if(reservedForUpdate.size() != 1) {
                throw new NotEnoughTicketsException();
            }
            SpecialPrice sp = specialPrice.get();
            ticketRepository.reserveTicket(transactionId, reservedForUpdate.stream().findFirst().orElseThrow(IllegalStateException::new),sp.getId(), locale.getLanguage(), category.getSrcPriceCts());
            specialPriceRepository.updateStatus(sp.getId(), Status.PENDING.toString(), sp.getSessionIdentifier());
        } else {
            ticketRepository.reserveTickets(transactionId, reservedForUpdate, ticketReservation.getTicketCategoryId(), locale.getLanguage(), category.getSrcPriceCts());
        }
        Ticket ticket = ticketRepository.findById(reservedForUpdate.get(0), category.getId());
        TicketPriceContainer priceContainer = TicketPriceContainer.from(ticket, null, event, discount);
        ticketRepository.updateTicketPrice(reservedForUpdate, category.getId(), event.getId(), category.getSrcPriceCts(), MonetaryUtil.unitToCents(priceContainer.getFinalPrice()), MonetaryUtil.unitToCents(priceContainer.getVAT()), MonetaryUtil.unitToCents(priceContainer.getAppliedDiscount()));
    }

    private void reserveAdditionalServicesForReservation(int eventId, String transactionId, ASReservationWithOptionalCodeModification additionalServiceReservation, PromoCodeDiscount discount) {
        Optional.ofNullable(additionalServiceReservation.getAdditionalServiceId())
            .flatMap(id -> optionally(() -> additionalServiceRepository.getById(id, eventId)))
            .filter(as -> additionalServiceReservation.getQuantity() > 0 && (as.isFixPrice() || Optional.ofNullable(additionalServiceReservation.getAmount()).filter(a -> a.compareTo(BigDecimal.ZERO) > 0).isPresent()))
            .map(as -> Pair.of(eventRepository.findById(eventId), as))
            .ifPresent(pair -> {
                Event e = pair.getKey();
                AdditionalService as = pair.getValue();
                IntStream.range(0, additionalServiceReservation.getQuantity())
                    .forEach(i -> {
                        AdditionalServicePriceContainer pc = AdditionalServicePriceContainer.from(additionalServiceReservation.getAmount(), as, e, discount);
                        additionalServiceItemRepository.insert(UUID.randomUUID().toString(), ZonedDateTime.now(Clock.systemUTC()), transactionId,
                            as.getId(), AdditionalServiceItemStatus.PENDING, eventId, pc.getSrcPriceCts(), unitToCents(pc.getFinalPrice()), unitToCents(pc.getVAT()), unitToCents(pc.getAppliedDiscount()));
                    });
            });

    }

    List<Integer> reserveTickets(int eventId, TicketReservationWithOptionalCodeModification ticketReservation, List<TicketStatus> requiredStatuses) {
        return reserveTickets(eventId, ticketReservation.getTicketCategoryId(), ticketReservation.getAmount(), requiredStatuses);
    }

    List<Integer> reserveTickets(int eventId , int categoryId, int qty, List<TicketStatus> requiredStatuses) {
        TicketCategory category = ticketCategoryRepository.getByIdAndActive(categoryId, eventId);
        List<String> statusesAsString = requiredStatuses.stream().map(TicketStatus::name).collect(toList());
        if(category.isBounded()) {
            return ticketRepository.selectTicketInCategoryForUpdateSkipLocked(eventId, categoryId, qty, statusesAsString);
        }
        return ticketRepository.selectNotAllocatedTicketsForUpdateSkipLocked(eventId, qty, statusesAsString);
    }

    Optional<SpecialPrice> fixToken(Optional<SpecialPrice> token, int ticketCategoryId, int eventId, Optional<String> specialPriceSessionId, TicketReservationWithOptionalCodeModification ticketReservation) {

        TicketCategory ticketCategory = ticketCategoryRepository.getByIdAndActive(ticketCategoryId, eventId);
        if(!ticketCategory.isAccessRestricted()) {
            return Optional.empty();
        }

        Optional<SpecialPrice> specialPrice = renewSpecialPrice(token, specialPriceSessionId);

        if(token.isPresent() && specialPrice.isEmpty()) {
            //there is a special price in the request but this isn't valid anymore
            throw new InvalidSpecialPriceTokenException();
        }

        boolean canAccessRestrictedCategory = specialPrice.isPresent()
                && specialPrice.get().getStatus() == SpecialPrice.Status.FREE
                && specialPrice.get().getTicketCategoryId() == ticketCategoryId;


        if (canAccessRestrictedCategory && ticketReservation.getAmount() > 1) {
            throw new NotEnoughTicketsException();
        }

        if (!canAccessRestrictedCategory && ticketCategory.isAccessRestricted()) {
            throw new MissingSpecialPriceTokenException();
        }

        return specialPrice;
    }

    public PaymentResult performPayment(PaymentSpecification spec,
                                        TotalPrice reservationCost,
                                        Optional<String> specialPriceSessionId,
                                        Optional<PaymentProxy> method) {
        PaymentProxy paymentProxy = evaluatePaymentProxy(method, reservationCost);

        if(!acquireGroupMembers(spec.getReservationId(), spec.getEvent())) {
            groupManager.deleteWhitelistedTicketsForReservation(spec.getReservationId());
            return PaymentResult.failed("error.STEP2_WHITELIST");
        }

        if(!initPaymentProcess(reservationCost, paymentProxy, spec)) {
            return PaymentResult.failed("error.STEP2_UNABLE_TO_TRANSITION");
        }

        try {
            PaymentResult paymentResult;
            ticketReservationRepository.lockReservationForUpdate(spec.getReservationId());
            //save billing data in case we have to go back to PENDING
            ticketReservationRepository.updateBillingData(spec.getVatStatus(), spec.getVatNr(), spec.getVatCountryCode(), spec.isInvoiceRequested(), spec.getReservationId());
            if(isDiscountCodeUsageExceeded(spec.getReservationId())) {
                return PaymentResult.failed(ErrorsCode.STEP_2_DISCOUNT_CODE_USAGE_EXCEEDED);
            }
            if(reservationCost.requiresPayment()) {
                paymentResult = paymentManager.lookupProviderByMethod(paymentProxy.getPaymentMethod(), spec.getPaymentContext())
                    .map( paymentProvider -> paymentProvider.getTokenAndPay(spec) )
                    .orElseGet( () -> PaymentResult.failed("error.STEP2_STRIPE_unexpected") );
            } else {
                paymentResult = PaymentResult.successful(NOT_YET_PAID_TRANSACTION_ID);
            }

            if (paymentResult.isSuccessful()) {
                generateInvoiceNumber(spec, reservationCost);
                completeReservation(spec, specialPriceSessionId, paymentProxy);
            } else if(paymentResult.isFailed()) {
                reTransitionToPending(spec.getReservationId());
            }
            return paymentResult;
        } catch(Exception ex) {
            //it is guaranteed that in this case we're dealing with "local" error (e.g. database failure),
            //thus it is safer to not rollback the reservation status
            log.error("unexpected error during payment confirmation", ex);
            return PaymentResult.failed("error.STEP2_STRIPE_unexpected");
        }

    }

    private void generateInvoiceNumber(PaymentSpecification spec, TotalPrice reservationCost) {
        if(!reservationCost.requiresPayment() || !spec.isInvoiceRequested() || !configurationManager.hasAllConfigurationsForInvoice(spec.getEvent())) {
            return;
        }

        String invoiceNumber = extensionManager.handleInvoiceGeneration(spec, reservationCost, ticketReservationRepository.getBillingDetailsForReservation(spec.getReservationId()))
            .flatMap(invoiceGeneration -> Optional.ofNullable(StringUtils.trimToNull(invoiceGeneration.getInvoiceNumber())))
            .orElseGet(() -> {
                int invoiceSequence = invoiceSequencesRepository.lockReservationForUpdate(spec.getEvent().getOrganizationId());
                invoiceSequencesRepository.incrementSequenceFor(spec.getEvent().getOrganizationId());
                String pattern = configurationManager.getStringConfigValue(Configuration.from(spec.getEvent().getOrganizationId(), spec.getEvent().getId(), ConfigurationKeys.INVOICE_NUMBER_PATTERN), "%d");
                return String.format(pattern, invoiceSequence);
            });

        ticketReservationRepository.setInvoiceNumber(spec.getReservationId(), invoiceNumber);
    }

    private boolean isDiscountCodeUsageExceeded(String reservationId) {
        TicketReservation reservation = ticketReservationRepository.findReservationById(reservationId);
        if(reservation.getPromoCodeDiscountId() != null) {
            final PromoCodeDiscount promoCode = promoCodeDiscountRepository.findById(reservation.getPromoCodeDiscountId());
            if(promoCode.getMaxUsage() == null) {
                return false;
            }
            int currentTickets = ticketReservationRepository.countTicketsInReservationForCategories(reservationId, categoriesOrNull(promoCode));
            return Boolean.TRUE.equals(serializedTransactionTemplate.execute(status -> {
                Integer confirmedPromoCode = promoCodeDiscountRepository.countConfirmedPromoCode(promoCode.getId(), categoriesOrNull(promoCode), reservationId, categoriesOrNull(promoCode) != null ? "X" : null);
                return promoCode.getMaxUsage() < currentTickets + confirmedPromoCode;
            }));
        }
        return false;
    }

    public boolean containsCategoriesLinkedToGroups(String reservationId, int eventId) {
        List<LinkedGroup> allLinks = groupManager.getLinksForEvent(eventId);
        if(allLinks.isEmpty()) {
            return false;
        }
        return ticketRepository.findTicketsInReservation(reservationId).stream()
            .anyMatch(t -> allLinks.stream().anyMatch(lg -> lg.getTicketCategoryId() == null || lg.getTicketCategoryId().equals(t.getCategoryId())));
    }

    private PaymentProxy evaluatePaymentProxy(Optional<PaymentProxy> method, TotalPrice reservationCost) {
        if(method.isPresent()) {
            return method.get();
        }
        if(reservationCost.getPriceWithVAT() == 0) {
            return PaymentProxy.NONE;
        }
        return PaymentProxy.STRIPE;
    }

    private boolean initPaymentProcess(TotalPrice reservationCost, PaymentProxy paymentProxy, PaymentSpecification spec) {
        if(reservationCost.getPriceWithVAT() > 0 && paymentProxy == PaymentProxy.STRIPE) {
            try {
                transitionToInPayment(spec);
            } catch (Exception e) {
                //unable to do the transition. Exiting.
                log.debug(String.format("unable to flag the reservation %s as IN_PAYMENT", spec.getReservationId()), e);
                return false;
            }
        }
        return true;
    }

    private boolean acquireGroupMembers(String reservationId, Event event) {
        int eventId = event.getId();
        List<LinkedGroup> linkedGroups = groupManager.getLinksForEvent(eventId);
        if(!linkedGroups.isEmpty()) {
            List<Ticket> ticketsInReservation = ticketRepository.findTicketsInReservation(reservationId);
            return Boolean.TRUE.equals(requiresNewTransactionTemplate.execute(status ->
                ticketsInReservation
                    .stream()
                    .filter(ticket -> linkedGroups.stream().anyMatch(c -> c.getTicketCategoryId() == null || c.getTicketCategoryId().equals(ticket.getCategoryId())))
                    .map(groupManager::acquireMemberForTicket)
                    .reduce(true, Boolean::logicalAnd)));
        }
        return true;
    }

    public void confirmOfflinePayment(Event event, String reservationId, String username) {
        TicketReservation ticketReservation = findById(reservationId).orElseThrow(IllegalArgumentException::new);
        ticketReservationRepository.lockReservationForUpdate(reservationId);
        Validate.isTrue(ticketReservation.getPaymentMethod() == PaymentProxy.OFFLINE, "invalid payment method");
        Validate.isTrue(ticketReservation.getStatus() == TicketReservationStatus.OFFLINE_PAYMENT, "invalid status");


        ticketReservationRepository.confirmOfflinePayment(reservationId, TicketReservationStatus.COMPLETE.name(), ZonedDateTime.now(event.getZoneId()));

        registerAlfioTransaction(event, reservationId, PaymentProxy.OFFLINE);

        auditingRepository.insert(reservationId, userRepository.findIdByUserName(username).orElse(null), event.getId(), Audit.EventType.RESERVATION_OFFLINE_PAYMENT_CONFIRMED, new Date(), Audit.EntityType.RESERVATION, ticketReservation.getId());

        CustomerName customerName = new CustomerName(ticketReservation.getFullName(), ticketReservation.getFirstName(), ticketReservation.getLastName(), event);
        acquireItems(TicketStatus.ACQUIRED, AdditionalServiceItemStatus.ACQUIRED,
            PaymentProxy.OFFLINE, reservationId, ticketReservation.getEmail(), customerName,
            ticketReservation.getUserLanguage(), ticketReservation.getBillingAddress(),
            ticketReservation.getCustomerReference(), event.getId());

        Locale language = findReservationLanguage(reservationId);

        final TicketReservation finalReservation = ticketReservationRepository.findReservationById(reservationId);
        createBillingDocumentModel(event, finalReservation, username);
        sendConfirmationEmail(event, findById(reservationId).orElseThrow(IllegalArgumentException::new), language);

        extensionManager.handleReservationConfirmation(finalReservation, ticketReservationRepository.getBillingDetailsForReservation(reservationId), event.getId());
    }

    void registerAlfioTransaction(Event event, String reservationId, PaymentProxy paymentProxy) {
        int priceWithVAT = totalReservationCostWithVAT(reservationId).getPriceWithVAT();
        Long platformFee = FeeCalculator.getCalculator(event, configurationManager)
            .apply(ticketRepository.countTicketsInReservation(reservationId), (long) priceWithVAT)
            .orElse(0L);

        //FIXME we must support multiple transactions for a reservation, otherwise we can't handle properly the case of ON_SITE payments

        if(paymentProxy != PaymentProxy.ON_SITE || transactionRepository.loadOptionalByReservationId(reservationId).isEmpty()) {
            String transactionId = paymentProxy.getKey() + "-" + System.currentTimeMillis();
            transactionRepository.insert(transactionId, null, reservationId, ZonedDateTime.now(event.getZoneId()),
                priceWithVAT, event.getCurrency(), "Offline payment confirmed for "+reservationId, paymentProxy.getKey(), platformFee, 0L);
        } else {
            log.warn("ON-Site check-in: ignoring transaction registration for reservationId {}", reservationId);
        }

    }


    public void sendConfirmationEmail(Event event, TicketReservation ticketReservation, Locale language) {
        String reservationId = ticketReservation.getId();

        OrderSummary summary = orderSummaryForReservationId(reservationId, event, language);

        Map<String, Object> reservationEmailModel = prepareModelForReservationEmail(event, ticketReservation);
        List<Mailer.Attachment> attachments = Collections.emptyList();

        if (configurationManager.canGenerateReceiptOrInvoiceToCustomer(event)) { // https://github.com/alfio-event/alf.io/issues/573
            attachments = generateAttachmentForConfirmationEmail(event, ticketReservation, language, reservationId, summary, reservationEmailModel);
        }

        notificationManager.sendSimpleEmail(event, ticketReservation.getEmail(), messageSource.getMessage("reservation-email-subject",
                new Object[]{getShortReservationID(event, reservationId), event.getDisplayName()}, language),
            () -> templateManager.renderTemplate(event, TemplateResource.CONFIRMATION_EMAIL, reservationEmailModel, language),
            attachments);
    }

    private List<Mailer.Attachment> generateAttachmentForConfirmationEmail(Event event,
                                                                           TicketReservation ticketReservation,
                                                                           Locale language,
                                                                           String reservationId,
                                                                           OrderSummary summary,
                                                                           Map<String, Object> reservationEmailModel) {
        final List<Mailer.Attachment> attachments = new ArrayList<>(1);
        if(mustGenerateBillingDocument(summary, ticketReservation)) { //#459 - include PDF invoice in reservation email
            BillingDocument.Type type = ticketReservation.getHasInvoiceNumber() ? INVOICE : RECEIPT;
            attachments.addAll(generateBillingDocumentAttachment(event, ticketReservation, language, getOrCreateBillingDocumentModel(event, ticketReservation, null), type));
        }

        notificationManager.sendSimpleEmail(event, ticketReservation.getEmail(), getReservationEmailSubject(event, language, "reservation-email-subject", getShortReservationID(event, reservationId)),
            () -> templateManager.renderTemplate(event, TemplateResource.CONFIRMATION_EMAIL, reservationEmailModel, language), attachments);
        if(!summary.getCashPayment() && !summary.getFree()) { //#459 - include PDF invoice in reservation email
            Map<String, String> model = new HashMap<>();
            model.put("reservationId", reservationId);
            model.put("eventId", Integer.toString(event.getId()));
            model.put("language", Json.toJson(language));
            model.put("reservationEmailModel", Json.toJson(reservationEmailModel));

            if (ticketReservation.getHasInvoiceNumber()) {
                attachments.add(new Mailer.Attachment("invoice.pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.INVOICE_PDF));
            } else if (!summary.getNotYetPaid()) {
                attachments.add(new Mailer.Attachment("receipt.pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.RECEIPT_PDF));
            }
        }
        return attachments;
    }

    public void sendReservationCompleteEmailToOrganizer(Event event, TicketReservation ticketReservation, Locale language) {
        Organization organization = organizationRepository.getById(event.getOrganizationId());
        List<String> cc = notificationManager.getCCForEventOrganizer(event);

        Map<String, Object> reservationEmailModel = prepareModelForReservationEmail(event, ticketReservation);

        String reservationId = ticketReservation.getId();
        OrderSummary summary = orderSummaryForReservationId(reservationId, event, language);

        List<Mailer.Attachment> attachments = Collections.emptyList();

        if (!configurationManager.canGenerateReceiptOrInvoiceToCustomer(event)) { // https://github.com/alfio-event/alf.io/issues/573
            attachments = generateAttachmentForConfirmationEmail(event, ticketReservation, language, reservationId, summary, reservationEmailModel);
        }


        notificationManager.sendSimpleEmail(event, organization.getEmail(), cc, "Reservation complete " + ticketReservation.getId(),
            () -> templateManager.renderTemplate(event, TemplateResource.CONFIRMATION_EMAIL_FOR_ORGANIZER, reservationEmailModel, language),
            attachments);
    }

    private static boolean mustGenerateBillingDocument(OrderSummary summary, TicketReservation ticketReservation) {
        return !summary.getFree() && (!summary.getNotYetPaid() || (summary.getWaitingForPayment() && ticketReservation.isInvoiceRequested()));
    }

    private static List<Mailer.Attachment> generateBillingDocumentAttachment(Event event,
                                                                             TicketReservation ticketReservation,
                                                                             Locale language,
                                                                             Map<String, Object> billingDocumentModel,
                                                                             BillingDocument.Type documentType) {
        Map<String, String> model = new HashMap<>();
        model.put("reservationId", ticketReservation.getId());
        model.put("eventId", Integer.toString(event.getId()));
        model.put("language", Json.toJson(language));
        model.put("reservationEmailModel", Json.toJson(billingDocumentModel));//ticketReservation.getHasInvoiceNumber()
        switch (documentType) {
            case INVOICE:
                return Collections.singletonList(new Mailer.Attachment("invoice.pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.INVOICE_PDF));
            case RECEIPT:
                return Collections.singletonList(new Mailer.Attachment("receipt.pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.RECEIPT_PDF));
            case CREDIT_NOTE:
                return Collections.singletonList(new Mailer.Attachment("credit-note.pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.CREDIT_NOTE_PDF));
            default:
                throw new IllegalStateException(documentType+" is not supported");
        }
    }

    private Locale findReservationLanguage(String reservationId) {
        return ticketReservationRepository.findOptionalReservationById(reservationId).map(TicketReservationManager::getReservationLocale).orElse(Locale.ENGLISH);
    }

    public void deleteOfflinePayment(Event event, String reservationId, boolean expired, boolean credit, String username) {
        TicketReservation reservation = findById(reservationId).orElseThrow(IllegalArgumentException::new);
        Validate.isTrue(reservation.getStatus() == OFFLINE_PAYMENT, "Invalid reservation status");
        if(credit) {
            creditReservation(reservation, username);
        } else {
            Map<String, Object> emailModel = prepareModelForReservationEmail(event, reservation);
            Locale reservationLanguage = findReservationLanguage(reservationId);
            String subject = getReservationEmailSubject(event, reservationLanguage, "reservation-email-expired-subject", reservation.getId());
            notificationManager.sendSimpleEmail(event, reservation.getEmail(), subject,
                () ->  templateManager.renderTemplate(event, TemplateResource.OFFLINE_RESERVATION_EXPIRED_EMAIL, emailModel, reservationLanguage)
            );
            cancelReservation(reservation, expired, username);
        }
    }

    private String getReservationEmailSubject(Event event, Locale reservationLanguage, String key, String id) {
        return messageSource.getMessage(key, new Object[]{id, event.getDisplayName()}, reservationLanguage);
    }

    @Transactional
    void issueCreditNoteForReservation(Event event, String reservationId, String username) {
        TicketReservation reservation = ticketReservationRepository.findReservationById(reservationId);
        ticketReservationRepository.updateReservationStatus(reservationId, TicketReservationStatus.CREDIT_NOTE_ISSUED.toString());
        auditingRepository.insert(reservationId, userRepository.nullSafeFindIdByUserName(username).orElse(null), event.getId(), Audit.EventType.CREDIT_NOTE_ISSUED, new Date(), RESERVATION, reservationId);
        Map<String, Object> model = prepareModelForReservationEmail(event, reservation);
        Map<String, Object> billingDocumentModel = createBillingDocumentModel(event, reservation, username, BillingDocument.Type.CREDIT_NOTE);
        notificationManager.sendSimpleEmail(event,
            reservation.getEmail(),
            getReservationEmailSubject(event, getReservationLocale(reservation), "credit-note-issued-email-subject", reservation.getId()),
            () -> templateManager.renderTemplate(event, TemplateResource.CREDIT_NOTE_ISSUED_EMAIL, model, getReservationLocale(reservation)),
            generateBillingDocumentAttachment(event, reservation, getReservationLocale(reservation), billingDocumentModel, CREDIT_NOTE)
        );
    }

    /**
     * Generates the billing document before updating the reservation, if needed.
     * This will ease the migration to the new BillingDocument structure
     *
     * @param event
     * @param reservation
     */
    @Transactional
    void ensureBillingDocumentIsPresent(Event event, TicketReservation reservation, String username) {
        if(reservation.getStatus() == PENDING || reservation.getStatus() == CANCELLED) {
            return;
        }
        OrderSummary summary = orderSummaryForReservationId(reservation.getId(), event, getReservationLocale(reservation));
        if(TicketReservationManager.mustGenerateBillingDocument(summary, reservation)) {
            getOrCreateBillingDocumentModel(event, reservation, username);
        }
    }

    @Transactional
    Map<String, Object> createBillingDocumentModel(Event event, TicketReservation reservation, String username) {
        return createBillingDocumentModel(event, reservation, username, reservation.getHasInvoiceNumber() ? INVOICE : RECEIPT);
    }

    private Map<String, Object> createBillingDocumentModel(Event event, TicketReservation reservation, String username, BillingDocument.Type type) {
        Optional<String> vat = getVAT(event);
        String existingModel = reservation.getInvoiceModel();
        boolean existingModelPresent = StringUtils.isNotBlank(existingModel);
        OrderSummary summary = existingModelPresent ? Json.fromJson(existingModel, OrderSummary.class) : orderSummaryForReservationId(reservation.getId(), event, getReservationLocale(reservation));
        Map<String, Object> model = prepareModelForReservationEmail(event, reservation, vat, summary);
        String number = reservation.getHasInvoiceNumber() ? reservation.getInvoiceNumber() : UUID.randomUUID().toString();
        if(!existingModelPresent) {
            //we still save invoice/receipt model to tickets_reservation for backward compatibility
            ticketReservationRepository.addReservationInvoiceOrReceiptModel(reservation.getId(), Json.toJson(summary));
        }
        AffectedRowCountAndKey<Long> doc = billingDocumentRepository.insert(event.getId(), reservation.getId(), number, type, Json.toJson(model), ZonedDateTime.now(), event.getOrganizationId());
        auditingRepository.insert(reservation.getId(), userRepository.nullSafeFindIdByUserName(username).orElse(null), event.getId(), Audit.EventType.BILLING_DOCUMENT_GENERATED, new Date(), Audit.EntityType.RESERVATION, reservation.getId(), singletonList(singletonMap("documentId", doc.getKey())));
        return model;
    }

    @Transactional
    public Map<String, Object> getOrCreateBillingDocumentModel(Event event, TicketReservation reservation, String username) {
        Optional<BillingDocument> existing = billingDocumentRepository.findLatestByReservationId(reservation.getId());
        if(existing.isPresent()) {
            return existing.get().getModel();
        }
        return createBillingDocumentModel(event, reservation, username);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareModelForReservationEmail(Event event, TicketReservation reservation, Optional<String> vat, OrderSummary summary) {
        Organization organization = organizationRepository.getById(event.getOrganizationId());
        String reservationUrl = reservationUrl(reservation.getId());
        String reservationShortID = getShortReservationID(event, reservation.getId());
        Optional<String> invoiceAddress = configurationManager.getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.INVOICE_ADDRESS));
        Optional<String> bankAccountNr = configurationManager.getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BANK_ACCOUNT_NR));
        Optional<String> bankAccountOwner = configurationManager.getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BANK_ACCOUNT_OWNER));
        Map<Integer, List<Ticket>> ticketsByCategory = ticketRepository.findTicketsInReservation(reservation.getId())
            .stream()
            .collect(groupingBy(Ticket::getCategoryId));
        final List<TicketWithCategory> ticketsWithCategory;
        if(!ticketsByCategory.isEmpty()) {
            ticketsWithCategory = ticketCategoryRepository.findByIds(ticketsByCategory.keySet())
                .stream()
                .flatMap(tc -> ticketsByCategory.get(tc.getId()).stream().map(t -> new TicketWithCategory(t, tc)))
                .collect(Collectors.toList());
        } else {
            ticketsWithCategory = Collections.emptyList();
        }
        //TODO euBusiness -> reverse charge applied
//        boolean euBusiness = StringUtils.isNotBlank(reservation.getVatCountryCode()) && StringUtils.isNotBlank(reservation.getVatNr())
//            && configurationManager.getRequiredValue(getSystemConfiguration(ConfigurationKeys.EU_COUNTRIES_LIST)).contains(reservation.getVatCountryCode())
//            && PriceContainer.VatStatus.isVatExempt(reservation.getVatStatus());
        return TemplateResource.prepareModelForConfirmationEmail(organization, event, reservation, vat, ticketsWithCategory, summary, reservationUrl, reservationShortID, invoiceAddress, bankAccountNr, bankAccountOwner);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareModelForReservationEmail(Event event, TicketReservation reservation) {
        Optional<String> vat = getVAT(event);
        OrderSummary summary = orderSummaryForReservationId(reservation.getId(), event, getReservationLocale(reservation));
        return prepareModelForReservationEmail(event, reservation, vat, summary);
    }

    private void transitionToInPayment(PaymentSpecification spec) {
        requiresNewTransactionTemplate.execute(status -> {
            int updatedReservation = ticketReservationRepository.updateTicketReservation(spec.getReservationId(),
                IN_PAYMENT.toString(), spec.getEmail(), spec.getCustomerName().getFullName(),
                spec.getCustomerName().getFirstName(), spec.getCustomerName().getLastName(),
                spec.getLocale().getLanguage(), spec.getBillingAddress(),null, PaymentProxy.STRIPE.toString(), spec.getCustomerReference());
            Validate.isTrue(updatedReservation == 1, "expected exactly one updated reservation, got " + updatedReservation);
            return null;
        });
    }

    public static boolean hasValidOfflinePaymentWaitingPeriod(PaymentContext context, ConfigurationManager configurationManager) {
        OptionalInt result = BankTransferManager.getOfflinePaymentWaitingPeriod(context, configurationManager);
        return result.isPresent() && result.getAsInt() >= 0;
    }

    /**
     * ValidPaymentMethod should be configured in organisation and event. And if even already started then event should not have PaymentProxy.OFFLINE as only payment method
     *
     * @param paymentMethodDTO
     * @param event
     * @param configurationManager
     * @return
     */
    public static boolean isValidPaymentMethod(PaymentManager.PaymentMethodDTO paymentMethodDTO, Event event, ConfigurationManager configurationManager) {
        return paymentMethodDTO.isActive()
            && event.getAllowedPaymentProxies().contains(paymentMethodDTO.getPaymentProxy())
            && (!paymentMethodDTO.getPaymentProxy().equals(PaymentProxy.OFFLINE) || hasValidOfflinePaymentWaitingPeriod(new PaymentContext(event), configurationManager));
    }

    private void reTransitionToPending(String reservationId) {
        int updatedReservation = ticketReservationRepository.updateReservationStatus(reservationId, TicketReservationStatus.PENDING.toString());
        Validate.isTrue(updatedReservation == 1, "expected exactly one updated reservation, got "+updatedReservation);
    }
    
    //check internal consistency between the 3 values
    public Optional<Triple<Event, TicketReservation, Ticket>> from(String eventName, String reservationId, String ticketIdentifier) {
        return optionally(() -> Triple.of(eventRepository.findByShortName(eventName), 
                ticketReservationRepository.findReservationById(reservationId), 
                ticketRepository.findByUUID(ticketIdentifier))).flatMap((x) -> {
                    
                    Ticket t = x.getRight();
                    Event e = x.getLeft();
                    TicketReservation tr = x.getMiddle();
                    
                    if(tr.getId().equals(t.getTicketsReservationId()) && e.getId() == t.getEventId()) {
                        return Optional.of(x);
                    } else {
                        return Optional.empty();
                    }
                    
                });
    }

    /**
     * Set the tickets attached to the reservation to the ACQUIRED state and the ticket reservation to the COMPLETE state. Additionally it will save email/fullName/billingaddress/userLanguage.
     */
    void completeReservation(PaymentSpecification spec, Optional<String> specialPriceSessionId, PaymentProxy paymentProxy) {
        String reservationId = spec.getReservationId();
        int eventId = spec.getEvent().getId();
        if(paymentProxy != PaymentProxy.OFFLINE) {
            TicketStatus ticketStatus = paymentProxy.isDeskPaymentRequired() ? TicketStatus.TO_BE_PAID : TicketStatus.ACQUIRED;
            AdditionalServiceItemStatus asStatus = paymentProxy.isDeskPaymentRequired() ? AdditionalServiceItemStatus.TO_BE_PAID : AdditionalServiceItemStatus.ACQUIRED;
            acquireItems(ticketStatus, asStatus, paymentProxy, reservationId, spec.getEmail(), spec.getCustomerName(), spec.getLocale().getLanguage(), spec.getBillingAddress(), spec.getCustomerReference(), eventId);
            final TicketReservation reservation = ticketReservationRepository.findReservationById(reservationId);
            extensionManager.handleReservationConfirmation(reservation, ticketReservationRepository.getBillingDetailsForReservation(reservationId), eventId);
        }
        //cleanup unused special price codes...
        specialPriceSessionId.ifPresent(specialPriceRepository::unbindFromSession);

        Date eventTime = new Date();
        auditingRepository.insert(reservationId, null, eventId, Audit.EventType.RESERVATION_COMPLETE, eventTime, Audit.EntityType.RESERVATION, reservationId);
        ticketReservationRepository.updateRegistrationTimestamp(reservationId, ZonedDateTime.now(spec.getEvent().getZoneId()));
        if(spec.isTcAccepted()) {
            auditingRepository.insert(reservationId, null, eventId, Audit.EventType.TERMS_CONDITION_ACCEPTED, eventTime, Audit.EntityType.RESERVATION, reservationId, singletonList(singletonMap("termsAndConditionsUrl", spec.getEvent().getTermsAndConditionsUrl())));
        }

        if(StringUtils.isNotBlank(spec.getEvent().getPrivacyPolicyLinkOrNull()) && spec.isPrivacyAccepted()) {
            auditingRepository.insert(reservationId, null, eventId, Audit.EventType.PRIVACY_POLICY_ACCEPTED, eventTime, Audit.EntityType.RESERVATION, reservationId, singletonList(singletonMap("privacyPolicyUrl", spec.getEvent().getPrivacyPolicyUrl())));
        }
    }

    private void acquireItems(TicketStatus ticketStatus, AdditionalServiceItemStatus asStatus, PaymentProxy paymentProxy,
                              String reservationId, String email, CustomerName customerName,
                              String userLanguage, String billingAddress, String customerReference, int eventId) {
        Map<Integer, Ticket> preUpdateTicket = ticketRepository.findTicketsInReservation(reservationId).stream().collect(toMap(Ticket::getId, Function.identity()));
        int updatedTickets = ticketRepository.updateTicketsStatusWithReservationId(reservationId, ticketStatus.toString());

        List<Ticket> ticketsInReservation = ticketRepository.findTicketsInReservation(reservationId);
        Map<Integer, Ticket> postUpdateTicket = ticketsInReservation.stream().collect(toMap(Ticket::getId, Function.identity()));
        postUpdateTicket.forEach((id, ticket) -> auditUpdateTicket(preUpdateTicket.get(id), Collections.emptyMap(), ticket, Collections.emptyMap(), eventId));

        int updatedAS = additionalServiceItemRepository.updateItemsStatusWithReservationUUID(reservationId, asStatus);
        Validate.isTrue(updatedTickets + updatedAS > 0, "no items have been updated");
        specialPriceRepository.updateStatusForReservation(singletonList(reservationId), Status.TAKEN.toString());
        ZonedDateTime timestamp = ZonedDateTime.now(ZoneId.of("UTC"));
        int updatedReservation = ticketReservationRepository.updateTicketReservation(reservationId, TicketReservationStatus.COMPLETE.toString(), email,
            customerName.getFullName(), customerName.getFirstName(), customerName.getLastName(), userLanguage, billingAddress, timestamp, paymentProxy.toString(), customerReference);
        Validate.isTrue(updatedReservation == 1, "expected exactly one updated reservation, got " + updatedReservation);
        waitingQueueManager.fireReservationConfirmed(reservationId);
        if(paymentProxy == PaymentProxy.PAYPAL || paymentProxy == PaymentProxy.ADMIN) {
            //we must notify the plugins about ticket assignment and send them by email
            Event event = eventRepository.findByReservationId(reservationId);
            TicketReservation reservation = findById(reservationId).orElseThrow(IllegalStateException::new);
            findTicketsInReservation(reservationId).stream()
                .filter(ticket -> StringUtils.isNotBlank(ticket.getFullName()) || StringUtils.isNotBlank(ticket.getFirstName()) || StringUtils.isNotBlank(ticket.getEmail()))
                .forEach(ticket -> {
                    Locale locale = Locale.forLanguageTag(ticket.getUserLanguage());
                    if(paymentProxy == PaymentProxy.PAYPAL) {
                        sendTicketByEmail(ticket, locale, event, getTicketEmailGenerator(event, reservation, locale));
                    }
                    extensionManager.handleTicketAssignment(ticket);
                });

        }
    }

    PartialTicketTextGenerator getTicketEmailGenerator(Event event, TicketReservation reservation, Locale locale) {
        return (t) -> {
            Map<String, Object> model = new HashMap<>();
            model.put("organization", organizationRepository.getById(event.getOrganizationId()));
            model.put("event", event);
            model.put("ticketReservation", reservation);
            model.put("ticketUrl", ticketUpdateUrl(event, t.getUuid()));
            model.put("ticket", t);
            TicketCategory category = ticketCategoryRepository.getById(t.getCategoryId());
            TemplateResource.fillTicketValidity(event, category, model);
            model.put("googleCalendarUrl", EventUtil.getGoogleCalendarURL(event, category, null));
            return templateManager.renderTemplate(event, TemplateResource.TICKET_EMAIL, model, locale);
        };
    }

    @Transactional
    public void cleanupExpiredReservations(Date expirationDate) {
        List<String> expiredReservationIds = ticketReservationRepository.findExpiredReservationForUpdate(expirationDate);
        if(expiredReservationIds.isEmpty()) {
            return;
        }
        
        specialPriceRepository.resetToFreeAndCleanupForReservation(expiredReservationIds);
        ticketRepository.resetCategoryIdForUnboundedCategories(expiredReservationIds);
        ticketFieldRepository.deleteAllValuesForReservations(expiredReservationIds);
        ticketRepository.freeFromReservation(expiredReservationIds);
        waitingQueueManager.cleanExpiredReservations(expiredReservationIds);

        //
        Map<Integer, List<ReservationIdAndEventId>> reservationIdsByEvent = ticketReservationRepository
            .getReservationIdAndEventId(expiredReservationIds)
            .stream()
            .collect(Collectors.groupingBy(ReservationIdAndEventId::getEventId));
        reservationIdsByEvent.forEach((eventId, reservations) -> {
            Event event = eventRepository.findById(eventId);
            List<String> reservationIds = reservations.stream().map(ReservationIdAndEventId::getId).collect(Collectors.toList());
            extensionManager.handleReservationsExpiredForEvent(event, reservationIds);
            billingDocumentRepository.deleteForReservations(reservationIds, eventId);
        });
        //
        ticketReservationRepository.remove(expiredReservationIds);
    }

    public void cleanupExpiredOfflineReservations(Date expirationDate) {
        ticketReservationRepository.findExpiredOfflineReservationsForUpdate(expirationDate)
            .forEach(this::cleanupOfflinePayment);
    }

    private void cleanupOfflinePayment(String reservationId) {
        try {
            nestedTransactionTemplate.execute((tc) -> {
                Event event = eventRepository.findByReservationId(reservationId);
                boolean enabled = configurationManager.getBooleanConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), AUTOMATIC_REMOVAL_EXPIRED_OFFLINE_PAYMENT), true);
                if (enabled) {
                    deleteOfflinePayment(event, reservationId, true, false, null);
                } else {
                    log.trace("Will not cleanup reservation with id {} because the automatic removal has been disabled", reservationId);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("error during reservation cleanup (id "+reservationId+")", e);
        }
    }

    /**
     * Finds all the reservations that are "stuck" in payment status.
     * This could happen when there is an internal error after a successful credit card charge.
     *
     * @param expirationDate expiration date
     */
    public void markExpiredInPaymentReservationAsStuck(Date expirationDate) {
        List<String> stuckReservations = ticketReservationRepository.findStuckReservationsForUpdate(expirationDate);
        if(!stuckReservations.isEmpty()) {
            ticketReservationRepository.updateReservationsStatus(stuckReservations, TicketReservationStatus.STUCK.name());

            Map<Integer, List<ReservationIdAndEventId>> reservationsGroupedByEvent = ticketReservationRepository
                .getReservationIdAndEventId(stuckReservations)
                .stream()
                .collect(Collectors.groupingBy(ReservationIdAndEventId::getEventId));

            reservationsGroupedByEvent.forEach((eventId, reservationIds) -> {
                Event event = eventRepository.findById(eventId);
                Organization organization = organizationRepository.getById(event.getOrganizationId());
                notificationManager.sendSimpleEmail(event, organization.getEmail(),
                    STUCK_TICKETS_SUBJECT,  () -> String.format(STUCK_TICKETS_MSG, event.getShortName()));

                extensionManager.handleStuckReservations(event, reservationIds.stream().map(ReservationIdAndEventId::getId).collect(toList()));
            });
        }
    }

    private static TotalPrice totalReservationCostWithVAT(PromoCodeDiscount promoCodeDiscount,
                                                          Event event,
                                                          PriceContainer.VatStatus reservationVatStatus,
                                                          List<Ticket> tickets,
                                                          Stream<Pair<AdditionalService, List<AdditionalServiceItem>>> additionalServiceItems) {

        List<TicketPriceContainer> ticketPrices = tickets.stream().map(t -> TicketPriceContainer.from(t, reservationVatStatus, event, promoCodeDiscount)).collect(toList());
        BigDecimal totalVAT = sum(ticketPrices, TicketPriceContainer::getVAT);
        BigDecimal totalDiscount = sum(ticketPrices, TicketPriceContainer::getAppliedDiscount);
        BigDecimal totalNET = sum(ticketPrices, TicketPriceContainer::getFinalPrice);
        int discountedTickets = (int) ticketPrices.stream().filter(t -> t.getAppliedDiscount().compareTo(BigDecimal.ZERO) > 0).count();
        int discountAppliedCount = discountedTickets <= 1 || promoCodeDiscount.getDiscountType() == DiscountType.FIXED_AMOUNT ? discountedTickets : 1;

        List<AdditionalServiceItemPriceContainer> asPrices = additionalServiceItems
            .flatMap(generateASIPriceContainers(event, null))
            .collect(toList());

        BigDecimal asTotalVAT = asPrices.stream().map(AdditionalServiceItemPriceContainer::getVAT).reduce(BigDecimal.ZERO, BigDecimal::add);
        //FIXME discount is not applied to donations, as it wouldn't make sense. Must be implemented for #111
        BigDecimal asTotalNET = asPrices.stream().map(AdditionalServiceItemPriceContainer::getFinalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TotalPrice(unitToCents(totalNET.add(asTotalNET)), unitToCents(totalVAT.add(asTotalVAT)), -(MonetaryUtil.unitToCents(totalDiscount)), discountAppliedCount);
    }

    private static BigDecimal sum(List<TicketPriceContainer> ticketPrices, Function<TicketPriceContainer, BigDecimal> propertyExtractor) {
        return ticketPrices.stream().map(propertyExtractor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Function<Pair<AdditionalService, List<AdditionalServiceItem>>, Stream<? extends AdditionalServiceItemPriceContainer>> generateASIPriceContainers(Event event, PromoCodeDiscount discount) {
        return p -> p.getValue().stream().map(asi -> AdditionalServiceItemPriceContainer.from(asi, p.getKey(), event, discount));
    }

    /**
     * Get the total cost with VAT if it's not included in the ticket price.
     * 
     * @param reservationId
     * @return
     */
    public TotalPrice totalReservationCostWithVAT(String reservationId) {
        TicketReservation reservation = ticketReservationRepository.findReservationById(reservationId);
        
        Optional<PromoCodeDiscount> promoCodeDiscount = Optional.ofNullable(reservation.getPromoCodeDiscountId()).map(promoCodeDiscountRepository::findById);
        
        Event event = eventRepository.findByReservationId(reservationId);
        List<Ticket> tickets = ticketRepository.findTicketsInReservation(reservationId);

        return totalReservationCostWithVAT(promoCodeDiscount.orElse(null), event, reservation.getVatStatus(), tickets, collectAdditionalServiceItems(reservationId, event));
    }

    private String formatPromoCode(PromoCodeDiscount promoCodeDiscount, List<Ticket> tickets) {

        List<Ticket> filteredTickets = tickets.stream().filter(ticket -> promoCodeDiscount.getCategories().contains(ticket.getCategoryId())).collect(toList());

        if (promoCodeDiscount.getCategories().isEmpty() || filteredTickets.isEmpty()) {
            return promoCodeDiscount.getPromoCode();
        }

        String formattedDiscountedCategories = filteredTickets.stream()
            .map(Ticket::getCategoryId)
            .collect(toSet())
            .stream()
            .map(categoryId -> ticketCategoryRepository.getByIdAndActive(categoryId, promoCodeDiscount.getEventId()).getName())
            .collect(Collectors.joining(", ", "(", ")"));


        return promoCodeDiscount.getPromoCode() + " " + formattedDiscountedCategories;
    }

    public OrderSummary orderSummaryForReservationId(String reservationId, Event event, Locale locale) {
        TicketReservation reservation = ticketReservationRepository.findReservationById(reservationId);
        TotalPrice reservationCost = totalReservationCostWithVAT(reservationId);
        PromoCodeDiscount discount = Optional.ofNullable(reservation.getPromoCodeDiscountId()).map(promoCodeDiscountRepository::findById).orElse(null);
        //
        boolean free = reservationCost.getPriceWithVAT() == 0;
        String vat = getVAT(event).orElse(null);
        String refundedAmount = null;

        boolean hasRefund = auditingRepository.countAuditsOfTypeForReservation(reservationId, Audit.EventType.REFUND) > 0;

        if(hasRefund) {
            refundedAmount = paymentManager.getInfo(reservation, event).getPaymentInformation().getRefundedAmount();
        }

        return new OrderSummary(reservationCost,
                extractSummary(reservationId, reservation.getVatStatus(), event, locale, discount, reservationCost), free,
                formatCents(reservationCost.getPriceWithVAT()), formatCents(reservationCost.getVAT()),
                reservation.getStatus() == TicketReservationStatus.OFFLINE_PAYMENT,
                reservation.getPaymentMethod() == PaymentProxy.ON_SITE, vat, reservation.getVatStatus(), refundedAmount);
    }
    
    List<SummaryRow> extractSummary(String reservationId, PriceContainer.VatStatus reservationVatStatus,
                                    Event event, Locale locale, PromoCodeDiscount promoCodeDiscount, TotalPrice reservationCost) {
        List<SummaryRow> summary = new ArrayList<>();
        List<TicketPriceContainer> tickets = ticketRepository.findTicketsInReservation(reservationId).stream()
            .map(t -> TicketPriceContainer.from(t, reservationVatStatus, event, promoCodeDiscount)).collect(toList());
        tickets.stream()
            .collect(Collectors.groupingBy(TicketPriceContainer::getCategoryId))
            .forEach((categoryId, ticketsByCategory) -> {
                final int subTotal = ticketsByCategory.stream().mapToInt(TicketPriceContainer::getSummarySrcPriceCts).sum();
                final int subTotalBeforeVat = ticketsByCategory.stream().mapToInt(TicketPriceContainer::getSummaryPriceBeforeVatCts).sum();
                TicketPriceContainer firstTicket = ticketsByCategory.get(0);
                final int ticketPriceCts = firstTicket.getSummarySrcPriceCts();
                final int priceBeforeVat = firstTicket.getSummaryPriceBeforeVatCts();
                String categoryName = ticketCategoryRepository.getByIdAndActive(categoryId, event.getId()).getName();
                summary.add(new SummaryRow(categoryName, formatCents(ticketPriceCts), formatCents(priceBeforeVat), ticketsByCategory.size(), formatCents(subTotal), formatCents(subTotalBeforeVat), subTotal, SummaryRow.SummaryType.TICKET));
            });

        summary.addAll(collectAdditionalServiceItems(reservationId, event)
            .map(entry -> {
                String language = locale.getLanguage();
                AdditionalServiceText title = additionalServiceTextRepository.findBestMatchByLocaleAndType(entry.getKey().getId(), language, AdditionalServiceText.TextType.TITLE);
                if(!title.getLocale().equals(language) || title.getId() == -1) {
                    log.debug("additional service {}: title not found for locale {}", title.getAdditionalServiceId(), language);
                }
                List<AdditionalServiceItemPriceContainer> prices = generateASIPriceContainers(event, null).apply(entry).collect(toList());
                AdditionalServiceItemPriceContainer first = prices.get(0);
                final int subtotal = prices.stream().mapToInt(AdditionalServiceItemPriceContainer::getSrcPriceCts).sum();
                final int subtotalBeforeVat = prices.stream().mapToInt(AdditionalServiceItemPriceContainer::getSummaryPriceBeforeVatCts).sum();
                return new SummaryRow(title.getValue(), formatCents(first.getSrcPriceCts()), formatCents(first.getSummaryPriceBeforeVatCts()), prices.size(), formatCents(subtotal), formatCents(subtotalBeforeVat), subtotal, SummaryRow.SummaryType.ADDITIONAL_SERVICE);
            }).collect(Collectors.toList()));

        Optional.ofNullable(promoCodeDiscount).ifPresent(promo -> {
            String formattedSingleAmount = "-" + (promo.getDiscountType() == DiscountType.FIXED_AMOUNT ? formatCents(promo.getDiscountAmount()) : (promo.getDiscountAmount()+"%"));
            summary.add(new SummaryRow(formatPromoCode(promo, ticketRepository.findTicketsInReservation(reservationId)),
                formattedSingleAmount,
                formattedSingleAmount,
                reservationCost.getDiscountAppliedCount(),
                formatCents(reservationCost.getDiscount()), formatCents(reservationCost.getDiscount()), reservationCost.getDiscount(), SummaryRow.SummaryType.PROMOTION_CODE));
        });
        return summary;
    }

    private Stream<Pair<AdditionalService, List<AdditionalServiceItem>>> collectAdditionalServiceItems(String reservationId, Event event) {
        return additionalServiceItemRepository.findByReservationUuid(reservationId)
            .stream()
            .collect(Collectors.groupingBy(AdditionalServiceItem::getAdditionalServiceId))
            .entrySet()
            .stream()
            .map(entry -> Pair.of(additionalServiceRepository.getById(entry.getKey(), event.getId()), entry.getValue()));
    }

    String reservationUrl(String reservationId) {
        return reservationUrl(reservationId, eventRepository.findByReservationId(reservationId));
    }

    public String reservationUrl(String reservationId, Event event) {
        TicketReservation reservation = ticketReservationRepository.findReservationById(reservationId);
        return StringUtils.removeEnd(configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BASE_URL)), "/")
                + "/event/" + event.getShortName() + "/reservation/" + reservationId + "?lang="+reservation.getUserLanguage();
    }

    String ticketUrl(Event event, String ticketId) {
        Ticket ticket = ticketRepository.findByUUID(ticketId);
        return StringUtils.removeEnd(configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BASE_URL)), "/")
                + "/event/" + event.getShortName() + "/ticket/" + ticketId + "?lang=" + ticket.getUserLanguage();
    }

    public String ticketUpdateUrl(Event event, String ticketId) {
        Ticket ticket = ticketRepository.findByUUID(ticketId);
        return StringUtils.removeEnd(configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BASE_URL)), "/")
            + "/event/" + event.getShortName() + "/ticket/" + ticketId + "/update?lang="+ticket.getUserLanguage();
    }

    public int maxAmountOfTicketsForCategory(int organizationId, int eventId, int ticketCategoryId) {
        return configurationManager.getIntConfigValue(Configuration.from(organizationId, eventId, ticketCategoryId, ConfigurationKeys.MAX_AMOUNT_OF_TICKETS_BY_RESERVATION), 5);
    }
    
    public Optional<TicketReservation> findById(String reservationId) {
        return ticketReservationRepository.findOptionalReservationById(reservationId);
    }

    private Optional<TicketReservation> findByIdForNotification(String reservationId, ZoneId eventZoneId, int quietPeriod) {
        return findById(reservationId).filter(notificationNotSent(eventZoneId, quietPeriod));
    }

    private static Predicate<TicketReservation> notificationNotSent(ZoneId eventZoneId, int quietPeriod) {
        return r -> r.latestNotificationTimestamp(eventZoneId)
                .map(t -> t.truncatedTo(ChronoUnit.DAYS).plusDays(quietPeriod).isBefore(ZonedDateTime.now(eventZoneId).truncatedTo(ChronoUnit.DAYS)))
                .orElse(true);
    }

    public void cancelPendingReservation(String reservationId, boolean expired, String username) {
        cancelPendingReservation(ticketReservationRepository.findReservationById(reservationId), expired, username);
    }

    private void cancelPendingReservation(TicketReservation reservation, boolean expired, String username) {
        Validate.isTrue(reservation.getStatus() == TicketReservationStatus.PENDING, "status is not PENDING");
        cancelReservation(reservation, expired, username);
    }

    private void cancelReservation(TicketReservation reservation, boolean expired, String username) {
        String reservationId = reservation.getId();
        Event event = eventRepository.findByReservationId(reservationId);
        cleanupReferencesToReservation(expired, username, reservationId, event);
        removeReservation(event, reservation, expired, username);
    }

    private void creditReservation(TicketReservation reservation, String username) {
        String reservationId = reservation.getId();
        Event event = eventRepository.findByReservationId(reservationId);
        ensureBillingDocumentIsPresent(event, reservation, username);
        issueCreditNoteForReservation(event, reservationId, username);
        cleanupReferencesToReservation(false, username, reservationId, event);
        extensionManager.handleReservationsCreditNoteIssuedForEvent(event, Collections.singletonList(reservationId));
    }

    private void cleanupReferencesToReservation(boolean expired, String username, String reservationId, Event event) {
        List<String> reservationIdsToRemove = singletonList(reservationId);
        specialPriceRepository.resetToFreeAndCleanupForReservation(reservationIdsToRemove);
        ticketRepository.resetCategoryIdForUnboundedCategories(reservationIdsToRemove);
        ticketFieldRepository.deleteAllValuesForReservations(reservationIdsToRemove);
        int updatedAS = additionalServiceItemRepository.updateItemsStatusWithReservationUUID(reservationId, expired ? AdditionalServiceItemStatus.EXPIRED : AdditionalServiceItemStatus.CANCELLED);
        int updatedTickets = ticketRepository.findTicketsInReservation(reservationId).stream().mapToInt(t -> ticketRepository.releaseExpiredTicket(reservationId, event.getId(), t.getId())).sum();
        Validate.isTrue(updatedTickets  + updatedAS > 0, "no items have been updated");
        waitingQueueManager.fireReservationExpired(reservationId);
        groupManager.deleteWhitelistedTicketsForReservation(reservationId);
        auditingRepository.insert(reservationId, userRepository.nullSafeFindIdByUserName(username).orElse(null), event.getId(), expired ? Audit.EventType.CANCEL_RESERVATION_EXPIRED : Audit.EventType.CANCEL_RESERVATION, new Date(), Audit.EntityType.RESERVATION, reservationId);
    }

    private void removeReservation(Event event, TicketReservation reservation, boolean expired, String username) {
        //handle removal of ticket
        String reservationIdToRemove = reservation.getId();
        List<String> wrappedReservationIdToRemove = Collections.singletonList(reservationIdToRemove);
        waitingQueueManager.cleanExpiredReservations(wrappedReservationIdToRemove);
        int result = billingDocumentRepository.deleteForReservation(reservationIdToRemove, event.getId());
        if(result > 0) {
            log.warn("deleted {} documents for reservation id {}", result, reservationIdToRemove);
        }
        //
        if(expired) {
            extensionManager.handleReservationsExpiredForEvent(event, wrappedReservationIdToRemove);
        } else {
            extensionManager.handleReservationsCancelledForEvent(event, wrappedReservationIdToRemove);
        }
        int removedReservation = ticketReservationRepository.remove(wrappedReservationIdToRemove);
        Validate.isTrue(removedReservation == 1, "expected exactly one removed reservation, got " + removedReservation);
        auditingRepository.insert(reservationIdToRemove, userRepository.nullSafeFindIdByUserName(username).orElse(null), event.getId(), expired ? Audit.EventType.CANCEL_RESERVATION_EXPIRED : Audit.EventType.CANCEL_RESERVATION, new Date(), Audit.EntityType.RESERVATION, reservationIdToRemove);
    }

    public Optional<SpecialPrice> getSpecialPriceByCode(String code) {
        return specialPriceRepository.getByCode(code);
    }

    public Optional<SpecialPrice> renewSpecialPrice(Optional<SpecialPrice> specialPrice, Optional<String> specialPriceSessionId) {
        Validate.isTrue(specialPrice.isPresent(), "special price is not present");

        SpecialPrice price = specialPrice.get();

        if(specialPriceSessionId.isEmpty()) {
            log.warn("cannot renew special price {}: session identifier not found or not matching", price.getCode());
            return Optional.empty();
        }

        if(price.getStatus() == Status.PENDING && !StringUtils.equals(price.getSessionIdentifier(), specialPriceSessionId.get())) {
            log.warn("cannot renew special price {}: session identifier not found or not matching", price.getCode());
            return Optional.empty();
        }

        if(price.getStatus() == Status.FREE) {
            specialPriceRepository.bindToSession(price.getId(), specialPriceSessionId.get());
            return getSpecialPriceByCode(price.getCode());
        } else if(price.getStatus() == Status.PENDING) {
            Optional<Ticket> optionalTicket = ticketRepository.findBySpecialPriceId(price.getId());
            if(optionalTicket.isPresent()) {
                cancelPendingReservation(optionalTicket.get().getTicketsReservationId(), false, null);
                return getSpecialPriceByCode(price.getCode());
            }
        }

        return specialPrice;
    }

    public List<Ticket> findTicketsInReservation(String reservationId) {
        return ticketRepository.findTicketsInReservation(reservationId);
    }

    public List<Triple<AdditionalService, List<AdditionalServiceText>, AdditionalServiceItem>> findAdditionalServicesInReservation(String reservationId) {
        return additionalServiceItemRepository.findByReservationUuid(reservationId).stream()
            .map(asi -> Triple.of(additionalServiceRepository.getById(asi.getAdditionalServiceId(), asi.getEventId()), additionalServiceTextRepository.findAllByAdditionalServiceId(asi.getAdditionalServiceId()), asi))
            .collect(Collectors.toList());
    }

    public Optional<String> getVAT(Event event) {
        return configurationManager.getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.VAT_NR));
    }

    public void updateTicketOwner(Ticket ticket,
                                  Locale locale,
                                  Event event,
                                  UpdateTicketOwnerForm updateTicketOwner,
                                  PartialTicketTextGenerator confirmationTextBuilder,
                                  PartialTicketTextGenerator ownerChangeTextBuilder,
                                  Optional<UserDetails> userDetails) {

        Ticket preUpdateTicket = ticketRepository.findByUUID(ticket.getUuid());
        if(preUpdateTicket.getLockedAssignment() && isTicketBeingReassigned(ticket, updateTicketOwner, event)) {
            log.warn("trying to update assignee for a locked ticket ({})", preUpdateTicket.getId());
            return;
        }

        Map<String, String> preUpdateTicketFields = ticketFieldRepository.findAllByTicketId(ticket.getId()).stream().collect(Collectors.toMap(TicketFieldValue::getName, TicketFieldValue::getValue));

        String newEmail = updateTicketOwner.getEmail().trim();
        CustomerName customerName = new CustomerName(updateTicketOwner.getFullName(), updateTicketOwner.getFirstName(), updateTicketOwner.getLastName(), event, false);
        ticketRepository.updateTicketOwner(ticket.getUuid(), newEmail, customerName.getFullName(), customerName.getFirstName(), customerName.getLastName());

        //
        Locale userLocale = Optional.ofNullable(StringUtils.trimToNull(updateTicketOwner.getUserLanguage())).map(Locale::forLanguageTag).orElse(locale);

        ticketRepository.updateOptionalTicketInfo(ticket.getUuid(), userLocale.getLanguage());
        ticketFieldRepository.updateOrInsert(updateTicketOwner.getAdditional(), ticket.getId(), event.getId());

        Ticket newTicket = ticketRepository.findByUUID(ticket.getUuid());
        if ((newTicket.getStatus() == TicketStatus.ACQUIRED || newTicket.getStatus() == TicketStatus.TO_BE_PAID)
            && (!equalsIgnoreCase(newEmail, ticket.getEmail()) || !equalsIgnoreCase(customerName.getFullName(), ticket.getFullName()))) {
            sendTicketByEmail(newTicket, userLocale, event, confirmationTextBuilder);
        }

        boolean admin = isAdmin(userDetails);

        if (!admin && StringUtils.isNotBlank(ticket.getEmail()) && !equalsIgnoreCase(newEmail, ticket.getEmail()) && ticket.getStatus() == TicketStatus.ACQUIRED) {
            Locale oldUserLocale = Locale.forLanguageTag(ticket.getUserLanguage());
            String subject = messageSource.getMessage("ticket-has-changed-owner-subject", new Object[] {event.getDisplayName()}, oldUserLocale);
            notificationManager.sendSimpleEmail(event, ticket.getEmail(), subject, () -> ownerChangeTextBuilder.generate(newTicket));
            if(event.getBegin().isBefore(ZonedDateTime.now(event.getZoneId()))) {
                Organization organization = organizationRepository.getById(event.getOrganizationId());
                notificationManager.sendSimpleEmail(event, organization.getEmail(), "WARNING: Ticket has been reassigned after event start", () -> ownerChangeTextBuilder.generate(newTicket));
            }
        }

        if(admin) {
            TicketReservation reservation = findById(ticket.getTicketsReservationId()).orElseThrow(IllegalStateException::new);
            //if the current user is admin, then it would be good to update also the name of the Reservation Owner
            String username = userDetails.orElseThrow().getUsername();
            log.warn("Reservation {}: forced assignee replacement old: {} new: {}", reservation.getId(), reservation.getFullName(), username);
            ticketReservationRepository.updateAssignee(reservation.getId(), username);
        }
        extensionManager.handleTicketAssignment(newTicket);



        Ticket postUpdateTicket = ticketRepository.findByUUID(ticket.getUuid());
        Map<String, String> postUpdateTicketFields = ticketFieldRepository.findAllByTicketId(ticket.getId()).stream().collect(Collectors.toMap(TicketFieldValue::getName, TicketFieldValue::getValue));

        auditUpdateTicket(preUpdateTicket, preUpdateTicketFields, postUpdateTicket, postUpdateTicketFields, event.getId());
    }

    boolean isTicketBeingReassigned(Ticket original, UpdateTicketOwnerForm updated, Event event) {
        if(StringUtils.isBlank(original.getEmail()) || StringUtils.isBlank(original.getFullName())) {
            return false;
        }
        CustomerName customerName = new CustomerName(updated.getFullName(), updated.getFirstName(), updated.getLastName(), event);
        return StringUtils.isNotBlank(original.getEmail()) && StringUtils.isNotBlank(original.getFullName())
            && (!equalsIgnoreCase(original.getEmail(), updated.getEmail()) || !equalsIgnoreCase(original.getFullName(), customerName.getFullName()));
    }

    private void auditUpdateTicket(Ticket preUpdateTicket, Map<String, String> preUpdateTicketFields, Ticket postUpdateTicket, Map<String, String> postUpdateTicketFields, int eventId) {
        DiffNode diffTicket = ObjectDifferBuilder.buildDefault().compare(postUpdateTicket, preUpdateTicket);
        DiffNode diffTicketFields = ObjectDifferBuilder.buildDefault().compare(postUpdateTicketFields, preUpdateTicketFields);
        FieldChangesSaver diffTicketVisitor = new FieldChangesSaver(preUpdateTicket, postUpdateTicket);
        FieldChangesSaver diffTicketFieldsVisitor = new FieldChangesSaver(preUpdateTicketFields, postUpdateTicketFields);
        diffTicket.visit(diffTicketVisitor);
        diffTicketFields.visit(diffTicketFieldsVisitor);

        List<Map<String, Object>> changes = new ArrayList<>(diffTicketVisitor.changes);
        changes.addAll(diffTicketFieldsVisitor.changes);

        auditingRepository.insert(preUpdateTicket.getTicketsReservationId(), null, eventId,
            Audit.EventType.UPDATE_TICKET, new Date(), Audit.EntityType.TICKET, Integer.toString(preUpdateTicket.getId()), changes);
    }


    private static class FieldChangesSaver implements DiffNode.Visitor {

        private final Object preBase;
        private final Object postBase;

        private final List<Map<String, Object>> changes = new ArrayList<>();


        FieldChangesSaver(Object preBase, Object postBase) {
            this.preBase = preBase;
            this.postBase = postBase;
        }

        @Override
        public void node(DiffNode node, Visit visit) {
            if(node.hasChanges() && node.getState() != DiffNode.State.UNTOUCHED && !node.isRootNode()) {
                Object baseValue = node.canonicalGet(preBase);
                Object workingValue = node.canonicalGet(postBase);
                HashMap<String, Object> change = new HashMap<>();
                change.put("propertyName", node.getPath().toString());
                change.put("state", node.getState());
                change.put("oldValue", baseValue);
                change.put("newValue", workingValue);
                changes.add(change);
            }
        }
    }

    private boolean isAdmin(Optional<UserDetails> userDetails) {
        return userDetails.flatMap(u -> u.getAuthorities().stream().map(a -> Role.fromRoleName(a.getAuthority())).filter(Role.ADMIN::equals).findFirst()).isPresent();
    }

    void sendTicketByEmail(Ticket ticket, Locale locale, Event event, PartialTicketTextGenerator confirmationTextBuilder) {
        TicketReservation reservation = ticketReservationRepository.findReservationById(ticket.getTicketsReservationId());
        TicketCategory ticketCategory = ticketCategoryRepository.getByIdAndActive(ticket.getCategoryId(), event.getId());
        notificationManager.sendTicketByEmail(ticket, event, locale, confirmationTextBuilder, reservation, ticketCategory);
    }

    public Optional<Triple<Event, TicketReservation, Ticket>> fetchComplete(String eventName, String ticketIdentifier) {
        return ticketRepository.findOptionalByUUID(ticketIdentifier)
            .flatMap(ticket -> from(eventName, ticket.getTicketsReservationId(), ticketIdentifier)
                .flatMap((triple) -> {
                    if(triple.getMiddle().getStatus() == TicketReservationStatus.COMPLETE) {
                        return Optional.of(triple);
                    } else {
                        return Optional.empty();
                    }
            }));
    }

    /**
     * Return a fully present triple only if the values are present (obviously) and the the reservation has a COMPLETE status and the ticket is considered assigned.
     *
     * @param eventName
     * @param ticketIdentifier
     * @return
     */
    public Optional<Triple<Event, TicketReservation, Ticket>> fetchCompleteAndAssigned(String eventName, String ticketIdentifier) {
        return fetchComplete(eventName, ticketIdentifier).flatMap((t) -> {
            if (t.getRight().getAssigned()) {
                return Optional.of(t);
            } else {
                return Optional.empty();
            }
        });
    }

    public void sendReminderForOfflinePayments() {
        Date expiration = truncate(addHours(new Date(), configurationManager.getIntConfigValue(Configuration.getSystemConfiguration(OFFLINE_REMINDER_HOURS), 24)), Calendar.DATE);
        ticketReservationRepository.findAllOfflinePaymentReservationForNotificationForUpdate(expiration).stream()
                .map(reservation -> {
                    Optional<Ticket> ticket = ticketRepository.findFirstTicketInReservation(reservation.getId());
                    Optional<Event> event = ticket.map(t -> eventRepository.findById(t.getEventId()));
                    Optional<Locale> locale = ticket.map(t -> Locale.forLanguageTag(t.getUserLanguage()));
                    return Triple.of(reservation, event, locale);
                })
                .filter(p -> p.getMiddle().isPresent())
                .filter(p -> {
                    Event event = p.getMiddle().get();
                    return truncate(addHours(new Date(), configurationManager.getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), OFFLINE_REMINDER_HOURS), 24)), Calendar.DATE).compareTo(p.getLeft().getValidity()) >= 0;
                })
                .map(p -> Triple.of(p.getLeft(), p.getMiddle().orElseThrow(), p.getRight().orElseThrow()))
                .forEach(p -> {
                    TicketReservation reservation = p.getLeft();
                    Event event = p.getMiddle();
                    Map<String, Object> model = prepareModelForReservationEmail(event, reservation);
                    Locale locale = p.getRight();
                    ticketReservationRepository.flagAsOfflinePaymentReminderSent(reservation.getId());
                    notificationManager.sendSimpleEmail(event, reservation.getEmail(), messageSource.getMessage("reservation.reminder.mail.subject", new Object[]{getShortReservationID(event, reservation.getId())}, locale), () -> templateManager.renderTemplate(event, TemplateResource.REMINDER_EMAIL, model, locale));
                });
    }

    //called each hour
    public void sendReminderForOfflinePaymentsToEventManagers() {
        eventRepository.findAllActives(ZonedDateTime.now(Clock.systemUTC())).stream().filter(event -> {
            ZonedDateTime dateTimeForEvent = ZonedDateTime.now(event.getZoneId());
            return dateTimeForEvent.truncatedTo(ChronoUnit.HOURS).getHour() == 5; //only for the events at 5:00 local time
        }).forEachOrdered(event -> {
            ZonedDateTime dateTimeForEvent = ZonedDateTime.now(event.getZoneId()).truncatedTo(ChronoUnit.DAYS).plusDays(1);
            List<TicketReservationInfo> reservations = ticketReservationRepository.findAllOfflinePaymentReservationWithExpirationBeforeForUpdate(dateTimeForEvent, event.getId());
            log.info("for event {} there are {} pending offline payments to handle", event.getId(), reservations.size());
            if(!reservations.isEmpty()) {
                Organization organization = organizationRepository.getById(event.getOrganizationId());
                List<String> cc = notificationManager.getCCForEventOrganizer(event);
                String subject = String.format("There are %d pending offline payments that will expire in event: %s", reservations.size(), event.getDisplayName());
                String baseUrl = configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), BASE_URL));
                Map<String, Object> model = TemplateResource.prepareModelForOfflineReservationExpiringEmailForOrganizer(event, reservations, baseUrl);
                notificationManager.sendSimpleEmail(event, organization.getEmail(), cc, subject, () ->
                    templateManager.renderTemplate(event, TemplateResource.OFFLINE_RESERVATION_EXPIRING_EMAIL_FOR_ORGANIZER, model, Locale.ENGLISH));
                extensionManager.handleOfflineReservationsWillExpire(event, reservations);
            }
        });
    }

    public void sendReminderForTicketAssignment() {
        getNotifiableEventsStream()
                .map(e -> Pair.of(e, ticketRepository.findAllReservationsConfirmedButNotAssignedForUpdate(e.getId())))
                .filter(p -> !p.getRight().isEmpty())
                .forEach(p -> Wrappers.voidTransactionWrapper(this::sendAssignmentReminder, p));
    }

    public void sendReminderForOptionalData() {
        getNotifiableEventsStream()
                .filter(e -> configurationManager.getBooleanConfigValue(Configuration.from(e.getOrganizationId(), e.getId(), OPTIONAL_DATA_REMINDER_ENABLED), true))
                .filter(e -> ticketFieldRepository.countAdditionalFieldsForEvent(e.getId()) > 0)
                .map(e -> Pair.of(e, ticketRepository.findAllAssignedButNotYetNotifiedForUpdate(e.getId())))
                .filter(p -> !p.getRight().isEmpty())
                .forEach(p -> Wrappers.voidTransactionWrapper(this::sendOptionalDataReminder, p));
    }

    private void sendOptionalDataReminder(Pair<Event, List<Ticket>> eventAndTickets) {
        nestedTransactionTemplate.execute(ts -> {
            Event event = eventAndTickets.getLeft();
            int daysBeforeStart = configurationManager.getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.ASSIGNMENT_REMINDER_START), 10);
            List<Ticket> tickets = eventAndTickets.getRight().stream().filter(t -> !ticketFieldRepository.hasOptionalData(t.getId())).collect(toList());
            Set<String> notYetNotifiedReservations = tickets.stream().map(Ticket::getTicketsReservationId).distinct().filter(rid -> findByIdForNotification(rid, event.getZoneId(), daysBeforeStart).isPresent()).collect(toSet());
            tickets.stream()
                    .filter(t -> notYetNotifiedReservations.contains(t.getTicketsReservationId()))
                    .forEach(t -> {
                        int result = ticketRepository.flagTicketAsReminderSent(t.getId());
                        Validate.isTrue(result == 1);
                        Map<String, Object> model = TemplateResource.prepareModelForReminderTicketAdditionalInfo(organizationRepository.getById(event.getOrganizationId()), event, t, ticketUpdateUrl(event, t.getUuid()));
                        Locale locale = Optional.ofNullable(t.getUserLanguage()).map(Locale::forLanguageTag).orElseGet(() -> findReservationLanguage(t.getTicketsReservationId()));
                        notificationManager.sendSimpleEmail(event, t.getEmail(), messageSource.getMessage("reminder.ticket-additional-info.subject", new Object[]{event.getDisplayName()}, locale), () -> templateManager.renderTemplate(event, TemplateResource.REMINDER_TICKET_ADDITIONAL_INFO, model, locale));
                    });
            return null;
        });
    }

    Stream<Event> getNotifiableEventsStream() {
        return eventRepository.findAll().stream()
                .filter(e -> {
                    int daysBeforeStart = configurationManager.getIntConfigValue(Configuration.from(e.getOrganizationId(), e.getId(), ConfigurationKeys.ASSIGNMENT_REMINDER_START), 10);
                    //we don't want to define events SO far away, don't we?
                    int days = (int) ChronoUnit.DAYS.between(ZonedDateTime.now(e.getZoneId()).toLocalDate(), e.getBegin().toLocalDate());
                    return days > 0 && days <= daysBeforeStart;
                });
    }

    private void sendAssignmentReminder(Pair<Event, Set<String>> p) {
        try {
            nestedTransactionTemplate.execute(ts -> {
                Event event = p.getLeft();
                ZoneId eventZoneId = event.getZoneId();
                int quietPeriod = configurationManager.getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.ASSIGNMENT_REMINDER_INTERVAL), 3);
                p.getRight().stream()
                    .map(id -> findByIdForNotification(id, eventZoneId, quietPeriod))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(reservation -> {
                        Map<String, Object> model = prepareModelForReservationEmail(event, reservation);
                        ticketReservationRepository.updateLatestReminderTimestamp(reservation.getId(), ZonedDateTime.now(eventZoneId));
                        Locale locale = findReservationLanguage(reservation.getId());
                        notificationManager.sendSimpleEmail(event, reservation.getEmail(), messageSource.getMessage("reminder.ticket-not-assigned.subject", new Object[]{event.getDisplayName()}, locale), () -> templateManager.renderTemplate(event, TemplateResource.REMINDER_TICKETS_ASSIGNMENT_EMAIL, model, locale));
                    });
                return null;
            });
        } catch (Exception ex) {
            log.warn("cannot send reminder message", ex);
        }
    }

    public TicketReservation findByPartialID(String reservationId) {
        Validate.notBlank(reservationId, "invalid reservationId");
        Validate.matchesPattern(reservationId, "^[^%]*$", "invalid character found");
        List<TicketReservation> results = ticketReservationRepository.findByPartialID(trimToEmpty(reservationId).toLowerCase() + "%");
        Validate.isTrue(results.size() > 0, "reservation not found");
        Validate.isTrue(results.size() == 1, "multiple results found. Try handling this reservation manually.");
        return results.get(0);
    }

    public String getShortReservationID(Event event, String reservationId) {
        return configurationManager.getShortReservationID(event, reservationId);
    }

    public int countAvailableTickets(Event event, TicketCategory category) {
        if(category.isBounded()) {
            return ticketRepository.countFreeTickets(event.getId(), category.getId());
        }
        return ticketRepository.countFreeTicketsForUnbounded(event.getId());
    }

    public void releaseTicket(Event event, TicketReservation ticketReservation, final Ticket ticket) {
        TicketCategory category = ticketCategoryRepository.getByIdAndActive(ticket.getCategoryId(), event.getId());
        if(!CategoryEvaluator.isTicketCancellationAvailable(ticketCategoryRepository, ticket)) {
            throw new IllegalStateException("Cannot release reserved tickets");
        }
        String reservationId = ticketReservation.getId();
        //#365 - reset UUID when releasing a ticket
        int result = ticketRepository.releaseTicket(reservationId, UUID.randomUUID().toString(), event.getId(), ticket.getId());
        Validate.isTrue(result == 1, String.format("Expected 1 row to be updated, got %d", result));
        if(category.isAccessRestricted() || !category.isBounded()) {
            ticketRepository.unbindTicketsFromCategory(event.getId(), category.getId(), singletonList(ticket.getId()));
        }
        Organization organization = organizationRepository.getById(event.getOrganizationId());
        Map<String, Object> model = TemplateResource.buildModelForTicketHasBeenCancelled(organization, event, ticket);
        Locale locale = Locale.forLanguageTag(Optional.ofNullable(ticket.getUserLanguage()).orElse("en"));
        notificationManager.sendSimpleEmail(event, ticket.getEmail(), messageSource.getMessage("email-ticket-released.subject",
                new Object[]{event.getDisplayName()}, locale),
                () -> templateManager.renderTemplate(event, TemplateResource.TICKET_HAS_BEEN_CANCELLED, model, locale));

        String ticketCategoryDescription = ticketCategoryDescriptionRepository.findByTicketCategoryIdAndLocale(category.getId(), ticket.getUserLanguage()).orElse("");

        List<AdditionalServiceItem> additionalServiceItems = additionalServiceItemRepository.findByReservationUuid(reservationId);
        Map<String, Object> adminModel = TemplateResource.buildModelForTicketHasBeenCancelledAdmin(organization, event, ticket,
            ticketCategoryDescription, additionalServiceItems, asi -> additionalServiceTextRepository.findByLocaleAndType(asi.getAdditionalServiceId(), locale.getLanguage(), AdditionalServiceText.TextType.TITLE));
        notificationManager.sendSimpleEmail(event, organization.getEmail(), messageSource.getMessage("email-ticket-released.admin.subject", new Object[]{ticket.getId(), event.getDisplayName()}, locale),
            () -> templateManager.renderTemplate(event, TemplateResource.TICKET_HAS_BEEN_CANCELLED_ADMIN, adminModel, locale));

        int deletedValues = ticketFieldRepository.deleteAllValuesForTicket(ticket.getId());
        log.debug("deleting {} field values for ticket {}", deletedValues, ticket.getId());

        auditingRepository.insert(reservationId, null, event.getId(), Audit.EventType.CANCEL_TICKET, new Date(), Audit.EntityType.TICKET, Integer.toString(ticket.getId()));

        if(ticketRepository.countTicketsInReservation(reservationId) == 0 && transactionRepository.loadOptionalByReservationId(reservationId).isEmpty()) {
            removeReservation(event, ticketReservation, false, null);
            auditingRepository.insert(reservationId, null, event.getId(), Audit.EventType.CANCEL_RESERVATION, new Date(), Audit.EntityType.RESERVATION, reservationId);
        } else {
            extensionManager.handleTicketCancelledForEvent(event, Collections.singletonList(ticket.getUuid()));
        }
    }

    public int getReservationTimeout(Event event) {
        return configurationManager.getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), RESERVATION_TIMEOUT), 25);
    }

    public void validateAndConfirmOfflinePayment(String reservationId, Event event, BigDecimal paidAmount, String username) {
        TicketReservation reservation = findByPartialID(reservationId);
        Optional<OrderSummary> optionalOrderSummary = optionally(() -> orderSummaryForReservationId(reservation.getId(), event, Locale.forLanguageTag(reservation.getUserLanguage())));
        Validate.isTrue(optionalOrderSummary.isPresent(), "Reservation not found");
        OrderSummary orderSummary = optionalOrderSummary.get();
        Validate.isTrue(MonetaryUtil.centsToUnit(orderSummary.getOriginalTotalPrice().getPriceWithVAT()).compareTo(paidAmount) == 0, "paid price differs from due price");
        confirmOfflinePayment(event, reservation.getId(), username);
    }

    private List<Pair<TicketReservation, OrderSummary>> fetchWaitingForPayment(int eventId, Event event, Locale locale) {
        return ticketReservationRepository.findAllReservationsWaitingForPaymentInEventId(eventId).stream()
            .map(id -> Pair.of(ticketReservationRepository.findReservationById(id), orderSummaryForReservationId(id, event, locale)))
            .collect(Collectors.toList());
    }

    public List<Pair<TicketReservation, OrderSummary>> getPendingPayments(Event event) {
        return fetchWaitingForPayment(event.getId(), event, Locale.ENGLISH);
    }

    public Integer getPendingPaymentsCount(int eventId) {
        return ticketReservationRepository.findAllReservationsWaitingForPaymentCountInEventId(eventId);
    }

    public List<Pair<TicketReservation, BillingDocument>> findAllInvoices(int eventId) {
        List<BillingDocument> documents = billingDocumentRepository.findAllOfTypeForEvent(BillingDocument.Type.INVOICE, eventId);
        Map<String, BillingDocument> documentsByReservationId = documents.stream().collect(toMap(BillingDocument::getReservationId, Function.identity()));
        return ticketReservationRepository.findByIds(documentsByReservationId.keySet()).stream()
            .map(r -> Pair.of(r, documentsByReservationId.get(r.getId())))
            .collect(toList());
    }

    public Integer countInvoices(int eventId) {
        return ticketReservationRepository.countInvoices(eventId);
    }


    public boolean hasPaidSupplements(String reservationId) {
        return additionalServiceItemRepository.hasPaidSupplements(reservationId);
    }

    void revertTicketsToFreeIfAccessRestricted(int eventId) {
        List<Integer> restrictedCategories = ticketCategoryRepository.findByEventId(eventId).stream()
            .filter(TicketCategory::isAccessRestricted)
            .map(TicketCategory::getId)
            .collect(toList());
        if(!restrictedCategories.isEmpty()) {
            int count = ticketRepository.revertToFreeForRestrictedCategories(eventId, restrictedCategories);
            if(count > 0) {
                log.debug("reverted {} tickets for categories {}", count, restrictedCategories);
            }
        }
    }


    public void updateReservation(String reservationId, CustomerName customerName, String email,
                                  String billingAddressCompany, String billingAddressLine1, String billingAddressLine2,
                                  String billingAddressZip, String billingAddressCity, String vatCountryCode, String customerReference,
                                  String vatNr,
                                  boolean isInvoiceRequested,
                                  boolean skipVatNr,
                                  boolean validated) {

        String completeBillingAddress = defaultString(StringUtils.trimToNull(billingAddressCompany), trimToEmpty(customerName.getFullName()))+"\n"+
            trimToEmpty(billingAddressLine1)+"\n"+
            trimToEmpty(billingAddressLine2)+"\n"+
            trimToEmpty(trimToEmpty(billingAddressZip)+" "+ trimToEmpty(billingAddressCity));

        completeBillingAddress = completeBillingAddress.replace("\n\n", "\n");

        ticketReservationRepository.updateTicketReservationWithValidation(reservationId,
            customerName.getFullName(), customerName.getFirstName(), customerName.getLastName(),
            email, billingAddressCompany, billingAddressLine1, billingAddressLine2, billingAddressZip,
            billingAddressCity, completeBillingAddress, vatCountryCode, vatNr, isInvoiceRequested, skipVatNr,
            customerReference,
            validated);
    }

    public void updateReservationInvoicingAdditionalInformation(String reservationId, TicketReservationInvoicingAdditionalInfo ticketReservationInvoicingAdditionalInfo) {
        ticketReservationRepository.updateInvoicingAdditionalInformation(reservationId, Json.toJson(ticketReservationInvoicingAdditionalInfo));
    }

    private static Locale getReservationLocale(TicketReservation reservation) {
        return StringUtils.isEmpty(reservation.getUserLanguage()) ? Locale.ENGLISH : Locale.forLanguageTag(reservation.getUserLanguage());
    }
}
