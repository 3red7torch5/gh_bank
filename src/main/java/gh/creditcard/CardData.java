package gh.creditcard;

import org.bukkit.Color;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;

public class CardData {
    String ownerUuid;
    String ownerName;
    int balance;
    String created;
    String lastUsed;
    Color cardColor;

    CardData(Player owner, Color color) {
        this.ownerUuid = owner.getUniqueId().toString();
        this.ownerName = owner.getName();
        this.balance = 0;
        this.cardColor = color;
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        this.created = sdf.format(new Date());
        this.lastUsed = this.created;
        org.bukkit.Bukkit.getLogger().info("New card created for " + ownerName + " (UUID: " + ownerUuid + ")");
    }

    CardData(String ownerUuid, String ownerName, int balance, String created, String lastUsed, int color) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.balance = balance;
        this.created = created;
        this.lastUsed = lastUsed;
        this.cardColor = Color.fromRGB(color);
    }

    void updateLastUsed() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        this.lastUsed = sdf.format(new Date());
    }

    int getBalance() {
        return balance;
    }

    void setBalance(int balance) {
        this.balance = balance;
    }

    void addBalance(int amount) {
        this.balance += amount;
    }

    boolean removeBalance(int amount) {
        if (balance >= amount) {
            balance -= amount;
            return true;
        }
        return false;
    }

    Color getCardColor() {
        return cardColor;
    }

    int getColorAsRGB() {
        return cardColor.asRGB();
    }

    String getOwnerUuid() {return ownerUuid;}
}