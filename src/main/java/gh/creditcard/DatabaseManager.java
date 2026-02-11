package gh.creditcard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

public class DatabaseManager {
    private final CreditCardPlugin plugin;
    private FileConfiguration cardsConfig;
    private File cardsFile;
    private FileConfiguration cooldownsConfig;
    private File cooldownsFile;
    private FileConfiguration skinsConfig;
    private File skinsFile;

    public DatabaseManager(CreditCardPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadCardsDatabase(CardManager cardManager) {
        cardsFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "cards.yml"));
        if (!cardsFile.exists()) {
            cardsConfig = new YamlConfiguration();
            cardsConfig.set("total-cards", 0);
            cardsConfig.set("cards", new HashMap<String, Object>());
            saveCardsDatabase(cardManager);
        } else {
            cardsConfig = YamlConfiguration.loadConfiguration(cardsFile);
        }
        ConfigurationSection cardsSection = cardsConfig.getConfigurationSection("cards");
        if (cardsSection != null) {
            for (String cardId : cardsSection.getKeys(false)) {
                String ownerUuid = cardsSection.getString(cardId + ".owner");
                String ownerName = cardsSection.getString(cardId + ".owner-name", "неизвестен");
                int balance = cardsSection.getInt(cardId + ".balance", 0);
                String created = cardsSection.getString(cardId + ".created", "");
                String lastUsed = cardsSection.getString(cardId + ".last-used", "");
                int color = cardsSection.getInt(cardId + ".color", org.bukkit.Color.fromRGB(255, 255, 255).asRGB());
                cardManager.addCard(cardId, new CardData(ownerUuid, ownerName, balance, created, lastUsed, color));
            }
        }
        plugin.getLogger().info("Loaded " + cardManager.getAllCards().size() + " cards from database");
    }

    public void saveCardsDatabase(CardManager cardManager) {
        if (cardsConfig == null) {
            cardsConfig = new YamlConfiguration();
        }
        cardsConfig.set("cards", null);
        for (Map.Entry<String, CardData> entry : cardManager.getAllCards().entrySet()) {
            String path = "cards." + entry.getKey();
            CardData card = entry.getValue();
            cardsConfig.set(path + ".owner", card.ownerUuid);
            cardsConfig.set(path + ".owner-name", card.ownerName);
            cardsConfig.set(path + ".balance", card.balance);
            cardsConfig.set(path + ".created", card.created);
            cardsConfig.set(path + ".last-used", card.lastUsed);
            cardsConfig.set(path + ".color", card.getColorAsRGB());
        }
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        cardsConfig.set("last-save", sdf.format(new Date()));
        cardsConfig.set("total-cards", cardManager.getAllCards().size());
        try {
            cardsConfig.save(cardsFile);
            plugin.getLogger().info("Saved " + cardManager.getAllCards().size() + " cards to database");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save cards database!", e);
        }
    }

    public void loadCooldownsDatabase(Map<UUID, Long> cooldowns) {
        cooldownsFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("cooldowns.file", "cooldowns.yml"));
        if (!cooldownsFile.exists()) {
            cooldownsConfig = new YamlConfiguration();
            cooldownsConfig.set("total-cooldowns", 0);
            cooldownsConfig.set("cooldowns", new HashMap<String, Object>());
            saveCooldownsDatabase(cooldowns);
        } else {
            cooldownsConfig = YamlConfiguration.loadConfiguration(cooldownsFile);
            ConfigurationSection cooldownsSection = cooldownsConfig.getConfigurationSection("cooldowns");
            if (cooldownsSection != null) {
                for (String playerUuidStr : cooldownsSection.getKeys(false)) {
                    try {
                        UUID playerUuid = UUID.fromString(playerUuidStr);
                        long cooldownEnd = cooldownsSection.getLong(playerUuidStr);
                        cooldowns.put(playerUuid, cooldownEnd);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Неверный UUID в базе данных кулдаунов: " + playerUuidStr);
                    }
                }
            }
        }
    }

    public void saveCooldownsDatabase(Map<UUID, Long> cooldowns) {
        if (cooldownsConfig == null) {
            cooldownsConfig = new YamlConfiguration();
        }
        if (cooldownsFile == null) {
            cooldownsFile = new File(plugin.getDataFolder(), "cooldowns.yml");
        }
        cooldownsConfig.set("cooldowns", null);
        for (Map.Entry<UUID, Long> entry : cooldowns.entrySet()) {
            String path = "cooldowns." + entry.getKey().toString();
            cooldownsConfig.set(path, entry.getValue());
        }
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        cooldownsConfig.set("version", 1);
        cooldownsConfig.set("last-save", sdf.format(new Date()));
        cooldownsConfig.set("total-cooldowns", cooldowns.size());
        try {
            cooldownsConfig.save(cooldownsFile);
            plugin.getLogger().info("Saved " + cooldowns.size() + " card creation cooldowns to database");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save card creation cooldowns database!", e);
        }
    }

    public void loadSkinsDatabase() {
        skinsFile = new File(plugin.getDataFolder(), "skins.yml");
        if (!skinsFile.exists()) {
            skinsConfig = new YamlConfiguration();
            skinsConfig.set("skins", new HashMap<String, Object>());
            saveSkinsDatabase();
        } else {
            skinsConfig = YamlConfiguration.loadConfiguration(skinsFile);
        }
        plugin.getLogger().info("Loaded skins database");
    }

    public void saveSkinsDatabase() {
        try {
            skinsConfig.save(skinsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save skins database!", e);
        }
    }

    public FileConfiguration getSkinsConfig() {
        return skinsConfig;
    }
}