package com.godweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
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
    
    // Cooldowns & Trackers
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, ComboData> comboTracker = new HashMap<>();
    private final Map<UUID, List<Arrow>> activePayloads = new HashMap<>();
    private final Map<UUID, Integer> airJumps = new HashMap<>();

    @Override
    public void onEnable() {
        this.weaponKey = new NamespacedKey(this, "weapon_id");
        this.payloadKey = new NamespacedKey(this, "is_payload");

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("godweapons") != null) {
            getCommand("godweapons").setExecutor(this);
            getCommand("godweapons").setTabCompleter(this);
        }
        
        startPassiveTracker();
        getLogger().info("⚡ God Weapons V4 online! Full Arsenal & GUI Loaded.");
    }

    /* =========================================================================
       PASSIVE TICK TRACKER (Runs every tick for held item effects)
       ========================================================================= */
    private void startPassiveTracker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String mainHandId = getWeaponId(p.getInventory().getItemInMainHand());
                    String offHandId = getWeaponId(p.getInventory().getItemInOffHand());
                    
                    // The Dimension Ripper (Sword)
                    if ("dimensionripper".equals(mainHandId)) {
                        ComboData cd = comboTracker.get(p.getUniqueId());
                        if (cd == null || System.currentTimeMillis() - cd.lastHit > 3000) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 20, 1, false, false, false));
                        }
                    }
                    
                    // The Abyssal Tether (Trident / Puff variant)
                    if ("abyssaltether".equals(mainHandId)) {
                        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                            p.setAllowFlight(true); 
                        }
                        if (((Entity) p).isOnGround()) {
                            airJumps.put(p.getUniqueId(), 5); 
                        } else if (p.getVelocity().getY() < -0.1) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 10, 0, false, false, false));
                        }
                    } else if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                        if (p.getAllowFlight()) p.setAllowFlight(false);
                    }

                    // Aegis of the Colossus (Shield - Works in Main or Offhand)
                    if ("colossusaegis".equals(mainHandId) || "colossusaegis".equals(offHandId)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20, 0, false, false, false));
                        // Scaling would theoretically be applied here using the GENERIC_SCALE attribute on Paper 1.20.5+
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       GUI INVENTORY MENU
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

        // Fill empty slots with glass panes for styling
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
                player.sendMessage(Component.text("Equipped: ", NamedTextColor.GRAY).append(event.getCurrentItem().getItemMeta().displayName()));
                worldSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f);
            }
        }
    }

    /* =========================================================================
       WEAPON FACTORY
       ========================================================================= */
    public ItemStack createWeapon(String type) {
        ItemStack item;
        ItemMeta meta;
        List<Component> lore = new ArrayList<>();

        switch (type.toLowerCase()) {
            case "dimensionripper": // 5.1
                item = new ItemStack(Material.NETHERITE_SWORD);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Dimension Ripper", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Frame Advantage", NamedTextColor.LIGHT_PURPLE));
                lore.add(Component.text("Right-Click: Phantom Dash", NamedTextColor.AQUA));
                lore.add(Component.text("Shift+Right-Click: Reality Cleave", NamedTextColor.RED));
                break;
            case "apexlancer": // 5.2
                item = new ItemStack(Material.TRIDENT); // Using Trident as visually closest to a spear
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Apex Lancer", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Momentum Siphon (Slipstream)", NamedTextColor.GRAY));
                lore.add(Component.text("Hold Right-Click: Cavalry Vault", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Judgment Barrage", NamedTextColor.GOLD));
                break;
            case "vampiricaxe": // 5.3
                item = new ItemStack(Material.NETHERITE_AXE);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Vampiric Greataxe", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Hemal Sight & Sanguine Bleed", NamedTextColor.RED));
                lore.add(Component.text("Right-Click: Life Siphon Beam (Max 20 Hearts)", NamedTextColor.DARK_RED));
                lore.add(Component.text("Shift+Right-Click: Berserk Pact", NamedTextColor.GRAY));
                break;
            case "abyssaltether": // 5.4
                item = new ItemStack(Material.TRIDENT);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Abyssal Tether", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Float & Inflation", NamedTextColor.GRAY));
                lore.add(Component.text("Right-Click: Sing", NamedTextColor.AQUA));
                lore.add(Component.text("Shift+Right-Click: Rest / Puff Up", NamedTextColor.RED));
                break;
            case "mjolnir": // 5.5
                item = new ItemStack(Material.MACE);
                meta = item.getItemMeta();
                meta.displayName(Component.text("Mjölnir, The Storm Gavel", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Kinetic Conductor (Fall Damage = Lightning)", NamedTextColor.GRAY));
                lore.add(Component.text("Right-Click: Mjölnir's Flight", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Supercharge", NamedTextColor.GOLD));
                break;
            case "colossusaegis": // 5.6
                item = new ItemStack(Material.SHIELD);
                meta = item.getItemMeta();
                meta.displayName(Component.text("Aegis of the Colossus", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Juggernaut Scale (2x Size, 3x Health)", NamedTextColor.GRAY));
                lore.add(Component.text("Right-Click: Colossal Guard & Perfect Parry", NamedTextColor.WHITE));
                lore.add(Component.text("Shift+Right-Click: Apex Repulsion", NamedTextColor.DARK_AQUA));
                break;
            case "directorscut": // 5.7
                item = new ItemStack(Material.BOW);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Director's Cut", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Scripted Trajectories", NamedTextColor.GRAY));
                lore.add(Component.text("Shoot Bow: Payload Arrow", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Cut to Black", NamedTextColor.DARK_RED));
                break;
            case "stratospherictnt": // 5.8
                item = new ItemStack(Material.TNT);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Stratospheric Payload", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Aerial Recon (Rocket Jump)", NamedTextColor.GRAY));
                lore.add(Component.text("Right-Click: Carpet Bomb Runway", NamedTextColor.YELLOW));
                lore.add(Component.text("Shift+Right-Click: Orbital Cataclysm", NamedTextColor.RED));
                break;
            default:
                return null;
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
        String weaponId = getWeaponId(player.getInventory().getItemInMainHand());
        if (weaponId == null) return;

        boolean isSneaking = player.isSneaking();

        switch (weaponId) {
            case "dimensionripper": // 5.1
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "reality_cleave", 8000)) launchRealityCleave(player);
                } else {
                    if (checkCooldown(player, "phantom_dash", 2500)) executePhantomDash(player);
                }
                break;
            case "abyssaltether": // 5.4
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "star_ko", 12000)) executeInnerGameRest(player); // Rest
                } else {
                    if (checkCooldown(player, "lullaby_wave", 7000)) launchLullabyWave(player); // Sing
                }
                break;
            case "mjolnir": // 5.5
                event.setCancelled(true);
                if (isSneaking) {
                    // Supercharge Toggle logic goes here
                } else {
                    if (checkCooldown(player, "mjolnir", 4000)) launchMjolnirHuracan(player);
                }
                break;
            case "colossusaegis": // 5.6
                if (isSneaking) {
                    event.setCancelled(true);
                    if (checkCooldown(player, "apex_repel", 15000)) {
                        // Implement Apex Repulsion (10 blocks up, then horizontal blast)
                        player.sendMessage(Component.text("Apex Repulsion fired!", NamedTextColor.AQUA));
                    }
                }
                break;
            case "directorscut": // 5.7
                if (isSneaking) {
                    event.setCancelled(true);
                    if (checkCooldown(player, "cut_to_black", 2000)) detonatePayloads(player);
                }
                break;
            case "stratospherictnt": // 5.8
                event.setCancelled(true);
                if (isSneaking) {
                    player.sendMessage(Component.text("Calling Orbital Cataclysm...", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("Painting Carpet Bomb Runway...", NamedTextColor.YELLOW));
                }
                break;
        }
    }

    @EventHandler
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        String weaponId = getWeaponId(player.getInventory().getItemInMainHand());
        if (weaponId == null) return;

        if (weaponId.equals("dimensionripper") && event.getEntity() instanceof LivingEntity target) {
            handleFrameAdvantage(player, target); // 5.1
        }
    }

    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if ("directorscut".equals(getWeaponId(event.getBow()))) {
            if (!(event.getProjectile() instanceof Arrow arrow)) return;
            arrow.getPersistentDataContainer().set(payloadKey, PersistentDataType.BYTE, (byte) 1);
            activePayloads.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(arrow);
            handleScriptedTrajectory(player, arrow); // 5.7
        }
    }

    /* =========================================================================
       THE ABYSSAL TETHER IMPLEMENTATION (5.4)
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
                if (radius > 10) { cancel(); return; } // Radius defined in 5.4
                
                for (int degree = 0; degree < 360; degree += 20) {
                    double rad = Math.toRadians(degree);
                    startLoc.getWorld().spawnParticle(Particle.NOTE, startLoc.clone().add(radius * Math.cos(rad), 0.5, radius * Math.sin(rad)), 1, 0.2, 0.2, 0.2, Math.random());
                }
                
                for (Entity e : startLoc.getWorld().getNearbyEntities(startLoc, radius, 2, radius)) {
                    if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId())) {
                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 255));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 1));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 70, 250)); 
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

        boolean hit = false;
        for (Entity e : player.getWorld().getNearbyEntities(player.getLocation(), 1.0, 1.0, 1.0)) {
            if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId())) {
                hit = true;
                le.setHealth(Math.max(0, le.getHealth() - 40.0)); 
                le.setVelocity(new Vector(0, 5.0, 0)); 
                
                worldSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f);
                worldSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
                player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 2);
                break; 
            }
        }
    }

    /* =========================================================================
       THE DIMENSION RIPPER IMPLEMENTATION (5.1)
       ========================================================================= */
    private void handleFrameAdvantage(Player player, LivingEntity target) {
        ComboData data = comboTracker.getOrDefault(player.getUniqueId(), new ComboData(target.getUniqueId(), 0));
        
        if (!data.targetId.equals(target.getUniqueId()) || System.currentTimeMillis() - data.lastHit > 3000) {
            data = new ComboData(target.getUniqueId(), 1); 
        } else {
            data.hits++;
        }
        data.lastHit = System.currentTimeMillis();
        comboTracker.put(player.getUniqueId(), data);

        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, Math.min(data.hits / 2, 4), false, false, true));
        
        Map<String, Long> pCooldowns = cooldowns.getOrDefault(player.getUniqueId(), new HashMap<>());
        for (Map.Entry<String, Long> entry : pCooldowns.entrySet()) {
            entry.setValue(entry.getValue() - 200);
        }
        
        worldSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.5f + (data.hits * 0.1f));
    }

    private void executePhantomDash(Player player) {
        Location start = player.getLocation();
        Vector dir = start.getDirection().normalize();
        
        RayTraceResult ray = player.getWorld().rayTraceBlocks(start.clone().add(0, 1, 0), dir, 6.0, FluidCollisionMode.NEVER, true);
        double distance = (ray != null && ray.getHitBlock() != null) ? start.distance(ray.getHitPosition().toLocation(player.getWorld())) - 0.5 : 6.0;
        
        Location end = start.clone().add(dir.multiply(distance));
        end.setYaw(start.getYaw());
        end.setPitch(start.getPitch());

        player.teleport(end);
        worldSound(start, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        worldSound(end, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f);
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
                            cancel(); 
                            return;
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       THE DIRECTOR'S CUT IMPLEMENTATION (5.7)
       ========================================================================= */
    private void handleScriptedTrajectory(Player player, Arrow arrow) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (arrow.isDead() || arrow.isInBlock()) {
                    startPayloadBeep(arrow);
                    cancel();
                    return;
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
       MJOLNIR IMPLEMENTATION (5.5)
       ========================================================================= */
    private void launchMjolnirHuracan(Player player) {
        Location start = player.getEyeLocation();
        Vector dir = start.getDirection().normalize();
        worldSound(start, Sound.ITEM_TRIDENT_THROW, 1.5f, 0.6f);
        
        ArmorStand stand = start.getWorld().spawn(start.clone().subtract(0, 1.2, 0), ArmorStand.class, s -> {
            s.setVisible(false); s.setMarker(true); s.setGravity(false); s.setSmall(true);
            s.getEquipment().setItemInMainHand(new ItemStack(Material.MACE));
        });

        new BukkitRunnable() {
            int ticks = 0;
            Location current = start.clone();
            @Override
            public void run() {
                if (ticks++ > 30 || !stand.isValid()) {
                    current.getWorld().strikeLightning(current);
                    stand.remove(); cancel(); return;
                }
                current.add(dir.clone().multiply(1.1));
                stand.teleport(current.clone().subtract(0, 0.7, 0));
                stand.setRightArmPose(new EulerAngle(ticks * 0.8, 0, 0));
                current.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, current, 8, 0.3, 0.3, 0.3, 0.05);
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       UTILITIES & COMMANDS
       ========================================================================= */
    private boolean checkCooldown(Player player, String ability, long durationMs) {
        Map<String, Long> pCooldowns = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long last = pCooldowns.getOrDefault(ability, 0L);
        long now = System.currentTimeMillis();
        
        if (now - last < durationMs) {
            player.sendMessage(Component.text("⏳ On cooldown: " + ((durationMs - (now - last))/1000 + 1) + "s", NamedTextColor.RED));
            return false;
        }
        pCooldowns.put(ability, now);
        return true;
    }

    private void worldSound(Location loc, Sound sound, float vol, float pitch) {
        if (loc.getWorld() != null) loc.getWorld().playSound(loc, sound, vol, pitch);
    }

    private static class ComboData {
        UUID targetId; 
        int hits;
        long lastHit;
        ComboData(UUID id, int h) { targetId = id; hits = h; lastHit = System.currentTimeMillis(); }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("godweapons.admin")) return true;
        
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player p) {
                openWeaponMenu(p);
            } else {
                sender.sendMessage("Only players can open the GUI.");
            }
            return true;
        }
        
        if (args[0].equalsIgnoreCase("give") && args.length >= 2) {
            Player target = (args.length >= 3) ? Bukkit.getPlayer(args[2]) : (sender instanceof Player p ? p : null);
            if (target == null) return true;
            
            ItemStack weapon = createWeapon(args[1]);
            if (weapon == null) {
                sender.sendMessage(Component.text("Unknown weapon. Open /gw menu instead.", NamedTextColor.RED));
                return true;
            }
            target.getInventory().addItem(weapon);
            sender.sendMessage(Component.text("Bestowed weapon upon " + target.getName(), NamedTextColor.GREEN));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "menu");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return List.of("dimensionripper", "apexlancer", "vampiricaxe", "abyssaltether", "mjolnir", "colossusaegis", "directorscut", "stratospherictnt");
        }
        if (args.length == 3) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return Collections.emptyList();
    }
}
