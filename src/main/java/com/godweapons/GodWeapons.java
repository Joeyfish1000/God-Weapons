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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
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
        getLogger().info("⚡ God Weapons V3 online! Riptide Aria (Jigglypuff Kit) loaded.");
    }

    /* =========================================================================
       PASSIVE TICK TRACKER (Runs every tick for held item effects)
       ========================================================================= */
    private void startPassiveTracker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String weaponId = getWeaponId(p.getInventory().getItemInMainHand());
                    
                    // Dimension Ripper: Starts slow
                    if ("dimensionripper".equals(weaponId)) {
                        ComboData cd = comboTracker.get(p.getUniqueId());
                        if (cd == null || System.currentTimeMillis() - cd.lastHit > 3000) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 20, 1, false, false, false));
                        }
                    }
                    
                    // Riptide Aria: Aerial superiority & floatiness
                    if ("riptidearia".equals(weaponId)) {
                        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                            p.setAllowFlight(true); // Enables the vanilla double-jump trigger
                        }
                        
                        if (((Entity) p).isOnGround()) {
                            airJumps.put(p.getUniqueId(), 5); // Reset multi-jumps on landing
                        } else if (p.getVelocity().getY() < -0.1) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 10, 0, false, false, false));
                        }
                    } else if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                        // Revoke flight trigger if they unequip it
                        if (p.getAllowFlight()) p.setAllowFlight(false);
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       WEAPON FACTORIES
       ========================================================================= */
    public ItemStack createWeapon(String type) {
        ItemStack item;
        ItemMeta meta;
        List<Component> lore = new ArrayList<>();

        switch (type.toLowerCase()) {
            case "mjolnir":
                item = new ItemStack(Material.NETHERITE_AXE);
                meta = item.getItemMeta();
                meta.displayName(Component.text("Mjölnir, Shatterer of Horizons", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Active: Thunderous Huracan [Right-Click]", NamedTextColor.YELLOW));
                break;
            case "dimensionripper":
                item = new ItemStack(Material.NETHERITE_SWORD);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Dimension Ripper", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Frame Advantage (Starts slow, speeds up on consecutive hits)", NamedTextColor.LIGHT_PURPLE));
                lore.add(Component.text("Active 1: Phantom Dash [Right-Click]", NamedTextColor.AQUA));
                lore.add(Component.text("Active 2: Reality Cleave [Shift + Right-Click]", NamedTextColor.RED));
                break;
            case "directorscut":
                item = new ItemStack(Material.BOW);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Director's Cut", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Scripted Trajectories (Slipstreams grant speed)", NamedTextColor.GRAY));
                lore.add(Component.text("Active 1: Payload Arrow [Shoot Bow]", NamedTextColor.YELLOW));
                lore.add(Component.text("Active 2: Cut to Black [Shift + Right-Click]", NamedTextColor.DARK_RED));
                break;
            case "riptidearia":
                item = new ItemStack(Material.TRIDENT);
                meta = item.getItemMeta();
                meta.displayName(Component.text("The Riptide Aria", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true));
                lore.add(Component.text("Passive: Balloon Drift (Slow fall, 5 mid-air jumps via sneak/jump)", NamedTextColor.GRAY));
                lore.add(Component.text("Active 1: Lullaby Wave [Right-Click]", NamedTextColor.AQUA));
                lore.add(Component.text("Active 2: The Inner Game (Star KO) [Shift + Right-Click]", NamedTextColor.RED));
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
            case "mjolnir":
                event.setCancelled(true);
                if (checkCooldown(player, "mjolnir", 4000)) launchMjolnirHuracan(player);
                break;
            case "dimensionripper":
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "reality_cleave", 8000)) launchRealityCleave(player);
                } else {
                    if (checkCooldown(player, "phantom_dash", 2500)) executePhantomDash(player);
                }
                break;
            case "directorscut":
                if (isSneaking) {
                    event.setCancelled(true);
                    if (checkCooldown(player, "cut_to_black", 2000)) detonatePayloads(player);
                }
                break;
            case "riptidearia":
                event.setCancelled(true);
                if (isSneaking) {
                    if (checkCooldown(player, "star_ko", 12000)) executeInnerGameRest(player);
                } else {
                    if (checkCooldown(player, "lullaby_wave", 7000)) launchLullabyWave(player);
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
            handleFrameAdvantage(player, target);
        }
    }

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

    /* =========================================================================
       THE RIPTIDE ARIA IMPLEMENTATION (Jigglypuff Kit)
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
        if ("riptidearia".equals(getWeaponId(p.getInventory().getItemInMainHand()))) {
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
            if ("riptidearia".equals(getWeaponId(p.getInventory().getItemInMainHand()))) {
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
                if (radius > 8) { cancel(); return; }
                
                // Draw acoustic ring
                for (int degree = 0; degree < 360; degree += 20) {
                    double rad = Math.toRadians(degree);
                    startLoc.getWorld().spawnParticle(Particle.NOTE, startLoc.clone().add(radius * Math.cos(rad), 0.5, radius * Math.sin(rad)), 1, 0.2, 0.2, 0.2, Math.random());
                }
                
                // Apply "Deep Sleep"
                for (Entity e : startLoc.getWorld().getNearbyEntities(startLoc, radius, 2, radius)) {
                    if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId())) {
                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 255));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 1));
                        le.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 70, 250)); // Negates jumping
                        
                        // Floating Z particles (Simulated with Notes)
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
        // Punish windup: Instant 3-second slumber
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 255, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, 250, false, false, false));
        
        player.sendMessage(Component.text("Zzz...", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, true));
        worldSound(player.getLocation(), Sound.ENTITY_CAT_PURREOW, 1.0f, 0.5f);

        // Frame-Perfect Hitbox Check
        boolean hit = false;
        for (Entity e : player.getWorld().getNearbyEntities(player.getLocation(), 1.0, 1.0, 1.0)) {
            if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId())) {
                hit = true;
                
                // Star KO Physics
                le.setHealth(Math.max(0, le.getHealth() - 40.0)); // True damage
                le.setVelocity(new Vector(0, 5.0, 0)); // Launch to stratosphere
                
                worldSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f);
                worldSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
                player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 2);
                
                player.sendMessage(Component.text("REST PUNISH!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
                break; 
            }
        }
    }

    /* =========================================================================
       THE DIMENSION RIPPER IMPLEMENTATION
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

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks++ > 40) { cancel(); return; }
                player.getWorld().spawnParticle(Particle.PORTAL, start.clone().add(0, 1, 0), 15, 0.5, 1, 0.5, 0.1);
                for (Entity e : player.getWorld().getNearbyEntities(start, 1.5, 1.5, 1.5)) {
                    if (e instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId())) le.damage(4.0, player);
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
                            cancel(); 
                            return;
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    /* =========================================================================
       THE DIRECTOR'S CUT IMPLEMENTATION
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
        if (arrows == null || arrows.isEmpty()) {
            player.sendMessage(Component.text("No active payloads to detonate!", NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("🎬 CUT!", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
        worldSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.5f);

        for (Arrow arrow : arrows) {
            if (arrow.isDead()) continue;
            Location loc = arrow.getLocation();
            loc.getWorld().createExplosion(loc, 3.0f, false, false, player);
            
            // Shower of 25 shrapnel arrows
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
       MJOLNIR IMPLEMENTATION 
       ========================================================================= */
    private void launchMjolnirHuracan(Player player) {
        Location start = player.getEyeLocation();
        Vector dir = start.getDirection().normalize();
        worldSound(start, Sound.ITEM_TRIDENT_THROW, 1.5f, 0.6f);
        
        ArmorStand stand = start.getWorld().spawn(start.clone().subtract(0, 1.2, 0), ArmorStand.class, s -> {
            s.setVisible(false); s.setMarker(true); s.setGravity(false); s.setSmall(true);
            s.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_AXE));
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
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = (args.length >= 3) ? Bukkit.getPlayer(args[2]) : (sender instanceof Player p ? p : null);
            if (target == null) return true;
            
            ItemStack weapon = createWeapon(args[1]);
            if (weapon == null) {
                sender.sendMessage(Component.text("Unknown weapon. Try: mjolnir, dimensionripper, directorscut, riptidearia", NamedTextColor.RED));
                return true;
            }
            target.getInventory().addItem(weapon);
            sender.sendMessage(Component.text("Bestowed weapon upon " + target.getName(), NamedTextColor.GREEN));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give");
        if (args.length == 2) return List.of("mjolnir", "dimensionripper", "directorscut", "riptidearia");
        if (args.length == 3) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return Collections.emptyList();
    }
}
