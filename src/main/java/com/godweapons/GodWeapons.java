package com.godweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public final class GodWeapons extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey weaponKey;
    private NamespacedKey payloadKey;
    private NamespacedKey carpetBombKey;

    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, ComboData> comboTracker = new HashMap<>();
    private final Map<UUID, List<Arrow>> activePayloads = new HashMap<>();
    private final Map<UUID, Integer> airJumps = new HashMap<>();
    
    // States
    private final Set<UUID> joustingStance = new HashSet<>();
    private final Map<UUID, Integer> mjolnirCharge = new HashMap<>();

    @Override
    public void onEnable() {
        this.weaponKey = new NamespacedKey(this, "weapon_id");
        this.payloadKey = new NamespacedKey(this, "is_payload");
        this.carpetBombKey = new NamespacedKey(this, "carpet_bomb");

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("godweapons") != null) {
            getCommand("godweapons").setExecutor(this);
            getCommand("godweapons").setTabCompleter(this);
        }
        startPassiveTracker();
        getLogger().info("⚡ God Weapons V6 online! Physics tuned, meters activated.");
    }

    /* =========================================================================
       PASSIVE TICK TRACKER
       ========================================================================= */
    private void startPassiveTracker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String mainHandId = getWeaponId(p.getInventory().getItemInMainHand());
                    String offHandId = getWeaponId(p.getInventory().getItemInOffHand());
                    
                    if ("dimensionripper".equals(mainHandId)) {
                        ComboData cd = comboTracker.get(p.getUniqueId());
                        if (cd == null || System.currentTimeMillis() - cd.lastHit > 3000) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 20, 1, false, false, false));
                        }
                    }
                    
                    if ("abyssaltether".equals(mainHandId)) {
                        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) p.setAllowFlight(true);
                        if (((Entity) p).isOnGround()) {
                            airJumps.put(p.getUniqueId(), 5);
                        } else if (p.getVelocity().getY() < -0.1) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 10, 0, false, false, false));
                        }
                    } else if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                        if (p.getAllowFlight()) p.setAllowFlight(false);
                    }

                    if ("colossusaegis".equals(mainHandId) || "colossusaegis".equals(offHandId)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20, 0, false, false, false));
                    }
                    
                    // Mjolnir active charge meter
                    if ("mjolnir".equals(mainHandId)) {
                        int charge = mjolnirCharge.getOrDefault(p.getUniqueId(), 0);
                        if (charge > 0) {
                            p.sendActionBar(Component.text("⚡ Mjölnir Charge: " + charge + "%", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true));
                            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 2, 0.5, 0.5, 0.5, 0.1);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       GUI & WEAPON FACTORY
       ========================================================================= */
    private void openWeaponMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, Component.text("The God Weapons Arsenal", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.BOLD, true));
        gui.setItem(10, createWeapon("dimensionripper"));
        gui.setItem(11, createWeapon("apexlancer"));
        gui.setItem(12, createWeapon("vampiricaxe"));
        gui.setItem(13, createWeapon("abyssaltether"));
        gui.setItem(14, createWeapon("mjolnir"));
        gui.setItem(15, createWeapon("colossusaegis"));
        gui.setItem(16, createWeapon("directorscut"));
        gui.setItem(22, createWeapon("stratospherictnt"));

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.displayName(Component.text(" "));
        glass.setItemMeta(meta);
        for(int i = 0; i < gui.getSize(); i++) {
            if(gui.getItem(i) == null) gui.setItem(i, glass);
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().equals(Component.text("The God Weapons Arsenal", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.BOLD, true))) {
            event.setCancelled(true);
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.BLACK_STAINED_GLASS_PANE) {
                Player player = (Player) event.getWhoClicked();
                player.getInventory().addItem(event.getCurrentItem().clone());
                worldSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 2.0f);
            }
        }
        
        if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.GRAY_STAINED_GLASS_PANE) {
            if (event.getCurrentItem().hasItemMeta() && event.getCurrentItem().getItemMeta().getDisplayName().contains("Ghost Handle")) {
                event.setCancelled(true);
            }
        }
    }

    public ItemStack createWeapon(String type) {
        ItemStack item;
        ItemMeta meta;
        List<Component> lore = new ArrayList<>();

        switch (type.toLowerCase()) {
            case "dimensionripper":
                item = new ItemStack(Material.NETHERITE_SWORD);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Dimension Ripper", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Frame Advantage", NamedTextColor.LIGHT_PURPLE));
                lore.add(Component.text("Right-Click: Phantom Dash", NamedTextColor.AQUA));
                lore.add(Component.text("Shift+Right-Click: Reality Cleave", NamedTextColor.RED));
                break;
            case "apexlancer": 
                item = new ItemStack(Material.NETHERITE_SWORD); 
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Apex Lancer", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Base Item: Netherite Spear", NamedTextColor.DARK_GRAY));
                lore.add(Component.text("Hold Right-Click: Cavalry Vault", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Judgment Barrage", NamedTextColor.GOLD));
                break;
            case "vampiricaxe": 
                item = new ItemStack(Material.NETHERITE_AXE);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Vampiric Greataxe", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Sanguine Bleed", NamedTextColor.RED));
                lore.add(Component.text("Right-Click: Life Siphon Beam", NamedTextColor.DARK_RED));
                lore.add(Component.text("Shift+Right-Click: Berserk Pact", NamedTextColor.GRAY));
                break;
            case "abyssaltether": 
                item = new ItemStack(Material.TRIDENT);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Abyssal Tether", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Float & Inflation", NamedTextColor.GRAY));
                lore.add(Component.text("Right-Click: Sing", NamedTextColor.AQUA));
                lore.add(Component.text("Shift+Right-Click: Rest / Puff Up", NamedTextColor.RED));
                break;
            case "mjolnir": 
                item = new ItemStack(Material.MACE);
                meta = item.getItemMeta();
                meta.displayName(Component.text("Mjölnir, The Storm Gavel", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Fall Damage = Lightning", NamedTextColor.GRAY));
                lore.add(Component.text("Right-Click: Mjölnir's Flight", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Supercharge", NamedTextColor.GOLD));
                break;
            case "colossusaegis": 
                item = new ItemStack(Material.SHIELD);
                meta = item.getItemMeta();
                meta.displayName(Component.text("Aegis of the Colossus", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: 2x Size, 3x Health", NamedTextColor.GRAY));
                lore.add(Component.text("Right-Click: Perfect Parry", NamedTextColor.WHITE));
                lore.add(Component.text("Shift+Right-Click: Apex Repulsion", NamedTextColor.DARK_AQUA));
                break;
            case "directorscut": 
                item = new ItemStack(Material.BOW);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Director's Cut", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Scripted Trajectories", NamedTextColor.GRAY));
                lore.add(Component.text("Shoot Bow: Payload Arrow", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Cut to Black", NamedTextColor.DARK_RED));
                break;
            case "stratospherictnt": 
                item = new ItemStack(Material.END_CRYSTAL);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Stratospheric Payload", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Base Item: Tactical Explosive", NamedTextColor.DARK_GRAY));
                lore.add(Component.text("Right-Click: Carpet Bomb Runway", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Orbital Cataclysm", NamedTextColor.RED));
                break;
            default: return null;
        }

        meta.lore(lore);
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, type.toLowerCase());
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String getWeaponId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
    }

    /* =========================================================================
       COMBAT & INTERACTION EVENT ROUTER
       ========================================================================= */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack handItem = player.getInventory().getItemInMainHand();
        String weaponId = getWeaponId(handItem);
        if (weaponId == null) return;

        boolean isSneaking = player.isSneaking();
        
        if (handItem.getType() == Material.END_CRYSTAL) event.setCancelled(true);

        switch (weaponId) {
            case "dimensionripper":
                event.setCancelled(true);
                if (isSneaking) { if (checkCooldown(player, "reality_cleave", 8000)) launchRealityCleave(player); } 
                else { if (checkCooldown(player, "phantom_dash", 2500)) executePhantomDash(player); }
                break;
                
            case "apexlancer":
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "judgment_barrage", 15000)) launchJudgmentBarrage(player);
                } else {
                    if (checkCooldown(player, "cavalry_vault", 4000)) {
                        joustingStance.add(player.getUniqueId());
                        worldSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.5f);
                        player.sendMessage(Component.text("Jousting Stance Active! (Strike an enemy!)", NamedTextColor.YELLOW));
                        new BukkitRunnable() { @Override public void run() { joustingStance.remove(player.getUniqueId()); } }.runTaskLater(this, 60);
                    }
                }
                break;

            case "vampiricaxe":
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "berserk_pact", 20000)) executeBerserkPact(player);
                } else {
                    if (checkCooldown(player, "life_siphon", 10000)) executeLifeSiphonBeam(player);
                }
                break;

            case "abyssaltether":
                event.setCancelled(true);
                if (isSneaking) { if (checkCooldown(player, "star_ko", 12000)) executeInnerGameRest(player); } 
                else { if (checkCooldown(player, "lullaby_wave", 7000)) launchLullabyWave(player); }
                break;
                
            case "mjolnir":
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "supercharge_toggle", 25000)) toggleSupercharge(player);
                } else {
                    if (checkCooldown(player, "mjolnir_throw", 5000)) launchMjolnirThrow(player, handItem);
                }
                break;

            case "colossusaegis":
                if (isSneaking) {
                    event.setCancelled(true);
                    if (checkCooldown(player, "apex_repel", 15000)) executeApexRepulsion(player);
                }
                break;
                
            case "directorscut":
                if (isSneaking) {
                    event.setCancelled(true);
                    if (checkCooldown(player, "cut_to_black", 2000)) detonatePayloads(player);
                }
                break;
                
            case "stratospherictnt":
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "orbital_cataclysm", 25000)) launchOrbitalCataclysm(player);
                } else {
                    if (checkCooldown(player, "carpet_bomb", 15000)) launchCarpetBomb(player);
                }
                break;
        }
    }

    @EventHandler
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        String weaponId = getWeaponId(player.getInventory().getItemInMainHand());
        if (weaponId == null) return;
        
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        if (weaponId.equals("dimensionripper")) {
            handleFrameAdvantage(player, target);
        }
        
        if (weaponId.equals("apexlancer") && joustingStance.contains(player.getUniqueId())) {
            joustingStance.remove(player.getUniqueId());
            double launchPower = player.isSprinting() ? 3.5 : 1.5;
            player.setVelocity(new Vector(0, launchPower, 0));
            event.setDamage(event.getDamage() * (player.isSprinting() ? 3.0 : 1.5));
            worldSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.0f);
            player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 1);
        }

        if (weaponId.equals("vampiricaxe")) {
            if (player.getCooledAttackStrength(0) == 1.0f) applySanguineBleed(player, target);
        }
        
        if (weaponId.equals("mjolnir")) {
            int charge = mjolnirCharge.getOrDefault(player.getUniqueId(), 0);
            if (charge > 0) {
                // Scale damage based on charge (up to +10 extra damage)
                event.setDamage(event.getDamage() + (charge * 0.10));
                player.getWorld().strikeLightningEffect(target.getLocation());
                
                // Deplete charge upon use
                charge -= 15;
                if (charge <= 0) {
                    charge = 0;
                    player.sendMessage(Component.text("Mjölnir charge depleted.", NamedTextColor.RED));
                }
                mjolnirCharge.put(player.getUniqueId(), charge);
            }
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            String mainHand = getWeaponId(player.getInventory().getItemInMainHand());
            if ("apexlancer".equals(mainHand) || "mjolnir".equals(mainHand) || "stratospherictnt".equals(mainHand)) {
                event.setCancelled(true);
                if ("mjolnir".equals(mainHand)) {
                    player.getWorld().strikeLightningEffect(player.getLocation());
                    for (Entity e : player.getNearbyEntities(4, 4, 4)) {
                        if (e instanceof LivingEntity le) le.damage(event.getDamage() * 1.5, player);
                    }
                }
            }
        }
    }

    /* =========================================================================
       AXE (5.3) - VAMPIRIC GREATAXE
       ========================================================================= */
    private void applySanguineBleed(Player wielder, LivingEntity target) {
        wielder.sendMessage(Component.text("Sanguine Bleed applied!", NamedTextColor.DARK_RED));
        worldSound(target.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.5f, 0.5f);
        
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ > 6 || target.isDead()) { cancel(); return; }
                target.damage(1.0);
                target.getWorld().spawnParticle(Particle.DUST, target.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, new Particle.DustOptions(Color.RED, 1.5f));
                wielder.setHealth(Math.min(wielder.getHealth() + 1.0, wielder.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
            }
        }.runTaskTimer(this, 0, 10);
    }

    private void executeLifeSiphonBeam(Player player) {
        RayTraceResult ray = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), 12.0, e -> e instanceof LivingEntity && !e.equals(player));
        if (ray == null || !(ray.getHitEntity() instanceof LivingEntity target)) {
            player.sendMessage(Component.text("No target in sight to siphon!", NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Siphoning life force...", NamedTextColor.DARK_RED));
        worldSound(player.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 1.0f, 0.5f);

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ >= 60) {
                    target.damage(6.0, player);
                    AttributeInstance maxHp = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    if (maxHp != null && maxHp.getBaseValue() < 40.0) {
                        maxHp.setBaseValue(Math.min(40.0, maxHp.getBaseValue() + 2.0));
                        player.sendMessage(Component.text("Max health permanently increased!", NamedTextColor.GREEN));
                    }
                    worldSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.5f);
                    cancel(); return;
                }
                if (target.isDead() || player.getLocation().distance(target.getLocation()) > 15.0) {
                    player.sendMessage(Component.text("Siphon broken!", NamedTextColor.GRAY));
                    cancel(); return;
                }
                Location pLoc = player.getEyeLocation();
                Location tLoc = target.getLocation().add(0, 1, 0);
                Vector dir = tLoc.toVector().subtract(pLoc.toVector());
                double dist = dir.length();
                dir.normalize();
                for (double d = 0; d < dist; d += 0.5) {
                    Location point = pLoc.clone().add(dir.clone().multiply(d));
                    player.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, new Particle.DustOptions(Color.MAROON, 1.2f));
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    private void executeBerserkPact(Player player) {
        AttributeInstance maxHp = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp == null || maxHp.getBaseValue() <= 4.0) {
            player.sendMessage(Component.text("You are too weak to make a pact!", NamedTextColor.RED));
            return;
        }
        maxHp.setBaseValue(maxHp.getBaseValue() - 2.0);
        
        // Amplifier '1' equals Level II in Minecraft
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1, false, false, true));
        
        worldSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f);
        player.getWorld().spawnParticle(Particle.LAVA, player.getLocation(), 30, 0.5, 1, 0.5, 0.1);
        player.sendMessage(Component.text("BLOOD PACT SEALED (Speed II, Strength II)!", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
    }

    /* =========================================================================
       SPEAR (5.2) - APEX LANCER
       ========================================================================= */
    private void launchJudgmentBarrage(Player player) {
        RayTraceResult ray = player.getWorld().rayTraceBlocks(player.getEyeLocation(), player.getEyeLocation().getDirection(), 40.0, FluidCollisionMode.NEVER, true);
        if (ray == null || ray.getHitBlock() == null) return;
        
        Location target = ray.getHitBlock().getLocation().add(0.5, 1, 0.5);
        worldSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 2.0f, 2.0f);
        
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ > 24) { cancel(); return; }
                for (int degree = 0; degree < 360; degree += 20) {
                    double rad = Math.toRadians(degree);
                    target.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, target.clone().add(3 * Math.cos(rad), 0.1, 3 * Math.sin(rad)), 1, 0, 0, 0, 0);
                }
            }
        }.runTaskTimer(this, 0, 1);

        new BukkitRunnable() {
            int waves = 0;
            @Override
            public void run() {
                if (waves++ > 16) { cancel(); return; }
                worldSound(target, Sound.ITEM_TRIDENT_THROW, 2.0f, 0.5f);
                target.getWorld().spawnParticle(Particle.END_ROD, target.clone().add(0, 10, 0), 50, 2.5, 5, 2.5, 0.5);
                for (Entity e : target.getWorld().getNearbyEntities(target, 4, 4, 4)) {
                    if (e instanceof LivingEntity le && !e.equals(player)) {
                        le.damage(4.0, player);
                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2));
                    }
                }
            }
        }.runTaskTimer(this, 24, 5);
    }

    /* =========================================================================
       MACE (5.5) - MJOLNIR
       ========================================================================= */
    private void toggleSupercharge(Player player) {
        if (mjolnirCharge.getOrDefault(player.getUniqueId(), 0) > 0) {
            player.sendMessage(Component.text("You are already charged!", NamedTextColor.RED));
            return;
        }
        
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 255, false, false, false));
        player.sendMessage(Component.text("Channeling storm...", NamedTextColor.YELLOW));
        
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ >= 60) { // Takes 3 seconds to fully charge
                    mjolnirCharge.put(player.getUniqueId(), 100);
                    player.removePotionEffect(PotionEffectType.SLOWNESS);
                    worldSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.5f);
                    player.sendMessage(Component.text("OVERCHARGED!", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
                    cancel(); return;
                }
                
                // Cinematic lightning striking around the player during windup
                if (ticks % 10 == 0) {
                    Location strike = player.getLocation().add((Math.random() - 0.5) * 6, 0, (Math.random() - 0.5) * 6);
                    player.getWorld().strikeLightningEffect(strike);
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    private void launchMjolnirThrow(Player player, ItemStack originalMace) {
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack ghost = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gMeta = ghost.getItemMeta();
        gMeta.displayName(Component.text("Ghost Handle (Returning...)", NamedTextColor.GRAY));
        ghost.setItemMeta(gMeta);
        player.getInventory().setItem(slot, ghost);

        Location start = player.getEyeLocation();
        Vector dir = start.getDirection().normalize();
        worldSound(start, Sound.ITEM_TRIDENT_THROW, 1.5f, 0.6f);
        
        ArmorStand stand = start.getWorld().spawn(start.clone().subtract(0, 1.2, 0), ArmorStand.class, s -> {
            s.setVisible(false); s.setMarker(true); s.setGravity(false); s.setSmall(true);
            s.getEquipment().setItemInMainHand(originalMace);
        });
        
        // Grab meter for damage scaling
        int charge = mjolnirCharge.getOrDefault(player.getUniqueId(), 0);
        final double baseDamage = 8.0 + (charge * 0.10);
        
        if (charge > 0) {
            charge -= 25; // Throw heavily depletes charge
            if (charge < 0) charge = 0;
            mjolnirCharge.put(player.getUniqueId(), charge);
        }

        new BukkitRunnable() {
            int ticks = 0;
            Location current = start.clone();
            @Override
            public void run() {
                if (ticks++ > 30 || !stand.isValid()) {
                    current.getWorld().strikeLightning(current);
                    stand.remove();
                    player.getInventory().setItem(slot, originalMace);
                    cancel(); return;
                }
                current.add(dir.clone().multiply(1.5));
                stand.teleport(current.clone().subtract(0, 0.7, 0));
                stand.setRightArmPose(new EulerAngle(ticks * 0.8, 0, 0));
                current.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, current, 8, 0.3, 0.3, 0.3, 0.05);
                
                for (Entity e : current.getWorld().getNearbyEntities(current, 1.5, 1.5, 1.5)) {
                    if (e instanceof LivingEntity le && !le.equals(player)) le.damage(baseDamage, player);
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       TNT (5.8) - STRATOSPHERIC PAYLOAD
       ========================================================================= */
    private void launchCarpetBomb(Player player) {
        Snowball canister = player.launchProjectile(Snowball.class);
        canister.getPersistentDataContainer().set(carpetBombKey, PersistentDataType.BYTE, (byte) 1);
        worldSound(player.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 1.0f, 0.5f);
        player.sendMessage(Component.text("Painting Runway...", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onCanisterHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Snowball sb && sb.getPersistentDataContainer().has(carpetBombKey, PersistentDataType.BYTE)) {
            Location hitLoc = event.getHitEntity() != null ? event.getHitEntity().getLocation() : (event.getHitBlock() != null ? event.getHitBlock().getLocation() : null);
            if (hitLoc == null) return;
            
            worldSound(hitLoc, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 2.0f, 0.5f);
            Vector dir = sb.getVelocity().normalize().setY(0);
            Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize().multiply(2.0); // 2-block spacing
            
            new BukkitRunnable() {
                int drops = 0;
                @Override
                public void run() {
                    if (drops++ >= 8) { cancel(); return; }
                    
                    Location centerDrop = hitLoc.clone().add(dir.clone().multiply(drops * 3));
                    centerDrop.setY(hitLoc.getY() + 60); // Drops from 60 blocks above ground
                    
                    // Spawn a layer (3 wide)
                    for (int i = -1; i <= 1; i++) {
                        Location exactDrop = centerDrop.clone().add(right.clone().multiply(i));
                        TNTPrimed tnt = hitLoc.getWorld().spawn(exactDrop, TNTPrimed.class);
                        tnt.setFuseTicks(80); // Ensure it survives the fall to the ground
                    }
                }
            }.runTaskTimer(this, 0, 5);
        }
    }

    private void launchOrbitalCataclysm(Player player) {
        RayTraceResult ray = player.getWorld().rayTraceBlocks(player.getEyeLocation(), player.getEyeLocation().getDirection(), 50.0, FluidCollisionMode.NEVER, true);
        if (ray == null || ray.getHitBlock() == null) return;
        
        Location target = ray.getHitBlock().getLocation();
        player.sendMessage(Component.text("ORBITAL CATACLYSM INBOUND.", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
        worldSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.5f);

        int[] ringCounts = {18, 12, 6, 1};
        double[] ringRadii = {9.0, 6.0, 3.0, 0.0};
        
        for (int i = 0; i < 4; i++) {
            int count = ringCounts[i];
            double radius = ringRadii[i];
            
            for (int j = 0; j < count; j++) {
                double angle = 2 * Math.PI * j / count;
                double x = Math.cos(angle) * radius;
                double z = Math.sin(angle) * radius;
                
                Location spawn = target.clone().add(x, 60, z); // 60 blocks above target
                
                TNTPrimed tnt = target.getWorld().spawn(spawn, TNTPrimed.class);
                tnt.setFuseTicks(80);
            }
        }
    }

    /* =========================================================================
       SHIELD (5.6) 
       ========================================================================= */
    private void executeApexRepulsion(Player player) {
        worldSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 2.0f, 0.5f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 100, 4, 1, 4, 0.2);
        
        List<Entity> caught = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(8, 4, 8)) {
            if (e instanceof LivingEntity && !e.equals(player)) {
                e.setVelocity(new Vector(0, 2.0, 0));
                caught.add(e);
            }
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                worldSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f);
                for (Entity e : caught) {
                    if (e.isValid()) {
                        Vector push = e.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(3.0);
                        e.setVelocity(push);
                    }
                }
            }
        }.runTaskLater(this, 15);
    }

    /* =========================================================================
       SWORD (5.1) 
       ========================================================================= */
    private void handleFrameAdvantage(Player player, LivingEntity target) {
        ComboData data = comboTracker.getOrDefault(player.getUniqueId(), new ComboData(target.getUniqueId(), 0));
        if (!data.targetId.equals(target.getUniqueId()) || System.currentTimeMillis() - data.lastHit > 3000) data = new ComboData(target.getUniqueId(), 1); 
        else data.hits++;
        data.lastHit = System.currentTimeMillis();
        comboTracker.put(player.getUniqueId(), data);
        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, Math.min(data.hits / 2, 4), false, false, true));
        worldSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.5f + (data.hits * 0.1f));
    }

    private void executePhantomDash(Player p) { 
        Location s = p.getLocation(); Vector d = s.getDirection().normalize();
        RayTraceResult r = p.getWorld().rayTraceBlocks(s.clone().add(0, 1, 0), d, 6.0, FluidCollisionMode.NEVER, true);
        double dist = (r != null && r.getHitBlock() != null) ? s.distance(r.getHitPosition().toLocation(p.getWorld())) - 0.5 : 6.0;
        Location e = s.clone().add(d.multiply(dist)); e.setYaw(s.getYaw()); e.setPitch(s.getPitch());
        p.teleport(e); worldSound(s, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f); worldSound(e, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f);
        
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ > 40) { cancel(); return; }
                p.getWorld().spawnParticle(Particle.PORTAL, s.clone().add(0, 1, 0), 15, 0.5, 1, 0.5, 0.1);
                for (Entity ent : p.getWorld().getNearbyEntities(s, 1.5, 1.5, 1.5)) {
                    if (ent instanceof LivingEntity le && !le.getUniqueId().equals(p.getUniqueId())) le.damage(4.0, p);
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    private void launchRealityCleave(Player player) {
        Location start = player.getEyeLocation();
        Vector dir = start.getDirection().normalize();
        worldSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.5f);
        worldSound(start, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.2f, 1.5f);

        new BukkitRunnable() {
            int ticks = 0;
            Location current = start.clone();
            final Set<UUID> hitTargets = new HashSet<>();
            @Override
            public void run() {
                if (ticks++ > 20) { cancel(); return; }
                current.add(dir.clone().multiply(1.0));
                
                Vector right = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
                for (double i = -1.5; i <= 1.5; i += 0.2) {
                    Location pLoc = current.clone().add(right.clone().multiply(i));
                    pLoc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, pLoc, 1, 0, 0, 0, 0);
                    pLoc.getWorld().spawnParticle(Particle.SWEEP_ATTACK, pLoc, 1, 0, 0, 0, 0);
                }

                for (Entity e : current.getWorld().getNearbyEntities(current, 2, 1, 2)) {
                    if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId()) && !hitTargets.contains(le.getUniqueId())) {
                        le.setHealth(Math.max(0, le.getHealth() - 10.0));
                        le.getWorld().spawnParticle(Particle.ENCHANTED_HIT, le.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.2);
                        if (le.isDead()) {
                            worldSound(le.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 1.5f);
                        } else {
                            hitTargets.add(le.getUniqueId());
                            cancel(); return;
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       BOW (5.7)
       ========================================================================= */
    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if ("directorscut".equals(getWeaponId(event.getBow()))) {
            if (!(event.getProjectile() instanceof Arrow arrow)) return;
            arrow.getPersistentDataContainer().set(payloadKey, PersistentDataType.BYTE, (byte) 1);
            activePayloads.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(arrow);
            handleScriptedTrajectory(player, arrow);
        }
    }

    private void handleScriptedTrajectory(Player player, Arrow arrow) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (arrow.isDead() || arrow.isInBlock()) {
                    startPayloadBeep(arrow);
                    cancel(); return;
                }
                Location loc = arrow.getLocation();
                loc.getWorld().spawnParticle(Particle.END_ROD, loc, 3, 0.1, 0.1, 0.1, 0);
                for (Entity e : loc.getWorld().getNearbyEntities(loc, 3, 3, 3)) {
                    if (e instanceof Player p) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 2, false, false, true));
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    private void startPayloadBeep(Arrow arrow) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (arrow.isDead()) { cancel(); return; }
                arrow.getWorld().playSound(arrow.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 2.0f);
                arrow.getWorld().spawnParticle(Particle.DUST, arrow.getLocation(), 5, 0.2, 0.2, 0.2, new Particle.DustOptions(Color.RED, 1.5f));
            }
        }.runTaskTimer(this, 0, 10);
    }

    private void detonatePayloads(Player player) {
        List<Arrow> arrows = activePayloads.get(player.getUniqueId());
        if (arrows == null || arrows.isEmpty()) return;

        player.sendMessage(Component.text("🎬 CUT!", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
        worldSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.5f);

        for (Arrow arrow : arrows) {
            if (arrow.isDead()) continue;
            Location loc = arrow.getLocation();
            loc.getWorld().createExplosion(loc, 3.0f, false, false, player);
            
            for (int i = 0; i < 25; i++) {
                Arrow shrapnel = loc.getWorld().spawn(loc.clone().add(0, 2, 0), Arrow.class);
                double rx = (Math.random() - 0.5) * 2.0;
                double rz = (Math.random() - 0.5) * 2.0;
                shrapnel.setVelocity(new Vector(rx, -0.8, rz).normalize().multiply(1.5));
                shrapnel.setShooter(player);
                shrapnel.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            }
            arrow.remove();
        }
        arrows.clear();
    }

    /* =========================================================================
       TRIDENT (5.4)
       ========================================================================= */
    private void triggerMidAirJump(Player player) {
        int jumps = airJumps.getOrDefault(player.getUniqueId(), 0);
        if (jumps > 0) {
            airJumps.put(player.getUniqueId(), jumps - 1);
            player.setVelocity(player.getVelocity().setY(0.6));
            worldSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.8f);
            player.getWorld().spawnParticle(Particle.NOTE, player.getLocation(), 5, 0.3, 0.1, 0.3, 0.5);
        }
    }

    @EventHandler
    public void onFlightAttempt(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        if ("abyssaltether".equals(getWeaponId(p.getInventory().getItemInMainHand()))) {
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                event.setCancelled(true);
                triggerMidAirJump(p);
            }
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player p = event.getPlayer();
        if (event.isSneaking() && !((Entity) p).isOnGround()) {
            if ("abyssaltether".equals(getWeaponId(p.getInventory().getItemInMainHand()))) {
                triggerMidAirJump(p);
            }
        }
    }

    private void launchLullabyWave(Player player) {
        Location startLoc = player.getLocation();
        worldSound(startLoc, Sound.BLOCK_NOTE_BLOCK_FLUTE, 1.5f, 0.8f);
        
        new BukkitRunnable() {
            int radius = 1;
            @Override
            public void run() {
                if (radius > 10) { cancel(); return; }
                
                for (int degree = 0; degree < 360; degree += 20) {
                    double rad = Math.toRadians(degree);
                    startLoc.getWorld().spawnParticle(Particle.NOTE, startLoc.clone().add(radius * Math.cos(rad), 0.5, radius * Math.sin(rad)), 1, 0.2, 0.2, 0.2, Math.random());
                }
                
                for (Entity e : startLoc.getWorld().getNearbyEntities(startLoc, radius, 2, radius)) {
                    if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId())) {
                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 255));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 1));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 70, 250)); 
                        
                        new BukkitRunnable() {
                            int ticks = 0;
                            @Override
                            public void run() {
                                if (ticks++ > 35 || le.isDead()) { cancel(); return; }
                                le.getWorld().spawnParticle(Particle.NOTE, le.getLocation().add(0, 2.2, 0), 1, 0, 0.1, 0, 1);
                            }
                        }.runTaskTimer(GodWeapons.this, 0, 2);
                    }
                }
                radius += 2;
            }
        }.runTaskTimer(this, 0, 2);
    }

    private void executeInnerGameRest(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 255, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, 250, false, false, false));
        
        player.sendMessage(Component.text("Zzz...", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, true));
        worldSound(player.getLocation(), Sound.ENTITY_CAT_PURREOW, 1.0f, 0.5f);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Entity e : player.getWorld().getNearbyEntities(player.getLocation(), 1.5, 1.5, 1.5)) {
                    if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId())) {
                        le.setHealth(Math.max(0, le.getHealth() - 40.0)); 
                        le.setVelocity(new Vector(0, 5.0, 0)); 
                        
                        worldSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f);
                        worldSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
                        player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 2);
                        player.sendMessage(Component.text("REST PUNISH!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
                        break; 
                    }
                }
            }
        }.runTaskLater(this, 20); 
    }

    /* =========================================================================
       UTILITIES
       ========================================================================= */
    private boolean checkCooldown(Player player, String ability, long durationMs) {
        Map<String, Long> pCooldowns = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long last = pCooldowns.getOrDefault(ability, 0L);
        long now = System.currentTimeMillis();
        if (now - last < durationMs) { player.sendMessage(Component.text("⏳ On cooldown: " + ((durationMs - (now - last))/1000 + 1) + "s", NamedTextColor.RED)); return false; }
        pCooldowns.put(ability, now); return true;
    }

    private void worldSound(Location loc, Sound sound, float vol, float pitch) {
        if (loc.getWorld() != null) loc.getWorld().playSound(loc, sound, vol, pitch);
    }

    private static class ComboData { 
        UUID targetId; int hits; long lastHit; 
        ComboData(UUID id, int h) { targetId = id; hits = h; lastHit = System.currentTimeMillis(); } 
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("godweapons.admin")) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) { if (sender instanceof Player p) openWeaponMenu(p); return true; }
        if (args[0].equalsIgnoreCase("give") && args.length >= 2) {
            Player target = (args.length >= 3) ? Bukkit.getPlayer(args[2]) : (sender instanceof Player p ? p : null);
            if (target != null) { target.getInventory().addItem(createWeapon(args[1])); target.sendMessage("Equipped " + args[1]); }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "menu");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return List.of("dimensionripper", "apexlancer", "vampiricaxe", "abyssaltether", "mjolnir", "colossusaegis", "directorscut", "stratospherictnt");
        return Collections.emptyList();
    }
}
