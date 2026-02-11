package gh.creditcard;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class CardManager {
    private final CreditCardPlugin plugin;
    private final Map<String, CardData> cards = new HashMap<>();
    private final Set<String> usedCardIds = new HashSet<>();
    private final Random random = new Random();

    public CardManager(CreditCardPlugin plugin) {
        this.plugin = plugin;
    }

    public CardData getCard(String cardId) {
        return cards.get(cardId);
    }

    public boolean hasCard(String cardId) {
        return cards.containsKey(cardId);
    }

    public void addCard(String cardId, CardData cardData) {
        cards.put(cardId, cardData);
        usedCardIds.add(cardId);
    }

    public void removeCard(String cardId) {
        cards.remove(cardId);
        usedCardIds.remove(cardId);
    }

    public Set<String> getUsedCardIds() {
        return usedCardIds;
    }

    public Map<String, CardData> getAllCards() {
        return cards;
    }

    public String createCard(Player player) {
        String cardId = generateUniqueCardId();
        Color randomColor = generateRandomColor();
        cards.put(cardId, new CardData(player, randomColor));
        usedCardIds.add(cardId);
        plugin.getLogger().info("Created new card " + cardId + " for " + player.getName());
        return cardId;
    }

    public ItemStack makeCard(String cardId, Material cardMaterial, String cardName,
                              List<String> cardLoreTemplate, Material currencyItem) {
        CardData cardData = cards.get(cardId);
        if (cardData == null) {
            plugin.getLogger().warning("Tried to create card item for non-existent card: " + cardId);
            return null;
        }
        ItemStack card = new ItemStack(cardMaterial);
        LeatherArmorMeta meta = (LeatherArmorMeta) card.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(cardName.replace('&', '§'));
            meta.setColor(cardData.getCardColor());
            meta.addItemFlags(
                    org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                    org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                    org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE,
                    org.bukkit.inventory.ItemFlag.HIDE_DESTROYS,
                    org.bukkit.inventory.ItemFlag.HIDE_PLACED_ON,
                    org.bukkit.inventory.ItemFlag.HIDE_DYE
            );
            List<String> lore = new ArrayList<>();
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("card_id", cardId);
            placeholders.put("balance", String.valueOf(cardData.getBalance()));
            placeholders.put("currency_item", currencyItem.name().toLowerCase().replace("_", " "));
            placeholders.put("owner_name", cardData.ownerName);
            for (String line : cardLoreTemplate) {
                String processedLine = line;
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    processedLine = processedLine.replace("%" + entry.getKey() + "%", entry.getValue());
                }
                lore.add(plugin.colorize(processedLine));
            }
            meta.setLore(lore);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(plugin.getCardIdKey(), PersistentDataType.STRING, cardId);
            card.setItemMeta(meta);
        }
        return card;
    }

    private String generateUniqueCardId() {
        String id;
        int attempts = 0;
        do {
            if (attempts++ > 100) {
                plugin.getLogger().warning("Failed to generate unique ID after 100 attempts!");
                id = String.format("%012d", System.currentTimeMillis() % 10000000000000L);
                if (!usedCardIds.contains(id)) {
                    usedCardIds.add(id);
                    return id;
                }
            }
            long randomId = Math.abs(random.nextLong()) % 1000000000000L;
            id = String.format("%08d", randomId);
            if (id.length() >= 8) {
                id = id.substring(0, 4) + "-" + id.substring(4, 8);
            }
        } while (usedCardIds.contains(id));
        usedCardIds.add(id);
        return id;
    }

    Color generateRandomColor() {
        int r = random.nextInt(256);
        int g = random.nextInt(256);
        int b = random.nextInt(256);
        return Color.fromRGB(r, g, b);
    }
}