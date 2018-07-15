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
package alfio.repository;

import alfio.model.whitelist.Whitelist;
import alfio.model.whitelist.WhitelistConfiguration;
import alfio.model.whitelist.WhitelistItem;
import alfio.model.whitelist.WhitelistedTicket;
import ch.digitalfondue.npjt.*;

import java.util.List;
import java.util.Optional;

@QueryRepository
public interface WhitelistRepository {

    String BY_EVENT_ID = "select * from whitelist_configuration where event_id_fk = :eventId";

    @Query("insert into whitelist(name, description, organization_id_fk) values(:name, :description, :orgId)")
    @AutoGeneratedKey("id")
    AffectedRowCountAndKey<Integer> insert(@Bind("name") String name,
                                           @Bind("description") String description,
                                           @Bind("orgId") int organizationId);

    @Query("select * from whitelist where id = :id")
    Whitelist getById(@Bind("id") int id);

    @Query("select * from whitelist where id = :id")
    Optional<Whitelist> getOptionalById(@Bind("id") int id);

    @Query("select * from whitelist where organization_id_fk = :organizationId")
    List<Whitelist> getAllForOrganization(@Bind("organizationId") int organizationId);

    @Query("insert into whitelist_configuration(whitelist_id_fk, event_id_fk, ticket_category_id_fk, type, match_type)" +
        " values(:whitelistId, :eventId, :ticketCategoryId, :type, :matchType)")
    @AutoGeneratedKey("id")
    AffectedRowCountAndKey<Integer> createConfiguration(@Bind("whitelistId") int whitelistId,
                                                        @Bind("eventId") int eventId,
                                                        @Bind("ticketCategoryId") Integer ticketCategoryId,
                                                        @Bind("type") WhitelistConfiguration.Type type,
                                                        @Bind("matchType") WhitelistConfiguration.MatchType matchType);

    @Query(type = QueryType.TEMPLATE,
        value = "insert into whitelist_item(whitelist_id_fk, value, description) values(:whitelistId, :value, :description)")
    String insertItemTemplate();

    @Query("select * from whitelist_item where whitelist_id_fk = :whitelistId order by value")
    List<WhitelistItem> getItems(@Bind("whitelistId") int whitelistId);

    @Query("insert into whitelisted_ticket(whitelist_item_id_fk, whitelist_configuration_id_fk, ticket_id_fk, requires_unique_value)" +
        " values(:itemId, :configurationId, :ticketId, :requiresUniqueValue)")
    int insertWhitelistedTicket(@Bind("itemId") int itemId, @Bind("configurationId") int configurationId, @Bind("ticketId") int ticketId, @Bind("requiresUniqueValue") Boolean requiresUniqueValue);

    @Query(BY_EVENT_ID +
        " and ticket_category_id_fk = :categoryId" +
        " union all select * from whitelist_configuration where event_id_fk = :eventId")
    List<WhitelistConfiguration> findActiveConfigurationsFor(@Bind("eventId") int eventId, @Bind("categoryId") int categoryId);

    @Query(BY_EVENT_ID)
    List<WhitelistConfiguration> findActiveConfigurationsForEvent(@Bind("eventId") int eventId);

    @Query("select * from whitelist_configuration where id = :configurationId")
    WhitelistConfiguration getConfiguration(@Bind("configurationId") int configurationId);

    @Query("select * from whitelist_configuration where id = :configurationId for update")
    WhitelistConfiguration getConfigurationForUpdate(@Bind("configurationId") int configurationId);


    @Query("select wi.* from whitelist_item wi, whitelist w, whitelist_configuration wc where wc.id = :configurationId" +
        " and wc.whitelist_id_fk = :whitelistId and wi.whitelist_id_fk = :whitelistId and wi.value = lower(:value)")
    Optional<WhitelistItem> findItemByValueExactMatch(@Bind("configurationId") int configurationId,
                                            @Bind("whitelistId") int whitelistId,
                                            @Bind("value") String value);

    @Query("select * from whitelisted_ticket where whitelist_item_id_fk = :itemId and whitelist_configuration_id_fk = :configurationId")
    Optional<WhitelistedTicket> findExistingWhitelistedTicket(@Bind("itemId") int itemId,
                                                              @Bind("configurationId") int configurationId);

    @Query("delete from whitelisted_ticket where ticket_id_fk in (:ticketIds)")
    int deleteExistingWhitelistedTickets(@Bind("ticketIds") List<Integer> ticketIds);


}
