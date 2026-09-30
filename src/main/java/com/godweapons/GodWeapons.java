package com.godweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.*;

public final class GodWeapons extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey weaponKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final long MJOLNIR_COOLDOWN_MS = 4000; // 4 seconds

    @Override
    public void onEnable() {
        this.weaponKey = new NamespacedKey(this, "weapon_id");

        // Register events & command executor
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("godweapons") != null) {
            getCommand("godweapons").setExecutor(this);
            getCommand("godweapons").setTabCompleter(this);
        }

        getLogger().info("⚡ God Weapons prototype online! Ready to shatter reality.");
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
        getLogger().info("God Weapons disabled. The gods have retreated.");
    }

    /* =========================================================================
       ITEM CREATION: MJÖLNIR
       ========================================================================= */
    public ItemStack createMjolnir() {
        ItemStack item = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Custom Adventure displayName & Lore
        meta.displayName(Component.text("Mjölnir, Shatterer of Horizons", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Mythic Artifact • Asgardian Lineage", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("Passive: Supercell Conduit", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true));
        lore.add(Component.text("Melee attacks on wet entities unleash chain lightning.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("Active: Thunderous Huracan [Right-Click]", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true));
        lore.add(Component.text("Hurls Mjölnir in a kinetic spiral wave. On collision,", NamedTextColor.GRAY));
        lore.add(Component.text("calls down an Asgardian lightning tempest.", NamedTextColor.GRAY));
        meta.lore(lore);

        // PersistentDataContainer Identity
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(weaponKey, PersistentDataType.STRING, "mjolnir");

        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);

        return item;
    }

    private boolean isMjolnir(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return "mjolnir".equals(pdc.get(weaponKey, PersistentDataType.STRING));
    }

    /* =========================================================================
       COMBAT & ABILITY LISTENERS
       ========================================================================= */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!isMjolnir(item)) return;
        event.setCancelled(true);

        // Check cooldown
        long now = System.currentTimeMillis();
        long lastUse = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastUse < MJOLNIR_COOLDOWN_MS) {
            long remaining = (MJOLNIR_COOLDOWN_MS - (now - lastUse)) / 1000 + 1;
            player.sendMessage(Component.text("⏳ Mjölnir is recharging: " + remaining + "s", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.5f);
            return;
        }

        cooldowns.put(player.getUniqueId(), now);
        launchMjolnirHuracan(player);
    }

    @EventHandler
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!isMjolnir(player.getInventory().getItemInMainHand())) return;

        // Passive Supercell procs when target or attacker is wet
        if (event.getEntity() instanceof LivingEntity target) {
            if (target.isInWaterOrRain() || player.isInWaterOrRain()) {
                target.getWorld().strikeLightningEffect(target.getLocation());
                target.damage(6.0, player);
                player.sendMessage(Component.text("⚡ Supercell Conduit triggered!", NamedTextColor.AQUA));
            }
        }
    }

    /* =========================================================================
       PHYSICS & PARTICLE ENGINE (HURACAN PROTOTYPE)
       ========================================================================= */
    private void launchMjolnirHuracan(Player player) {
        Location startLoc = player.getEyeLocation();
        Vector direction = startLoc.getDirection().normalize();
        World world = player.getWorld();

        // Audio layer 1: Kinetic release & bass crackle
        world.playSound(startLoc, Sound.ITEM_TRIDENT_THROW, 1.5f, 0.6f);
        world.playSound(startLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.8f);

        // Visual spinning projectile entity (Invisible ArmorStand holding the axe)
        ArmorStand projectile = world.spawn(startLoc.clone().subtract(0, 1.2, 0), ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_AXE));
        });

        new BukkitRunnable() {
            int ticks = 0;
            Location currentLoc = startLoc.clone();
            final Set<UUID> hitEntities = new HashSet<>();

            @Override
            public void run() {
                if (ticks++ > 30 || !projectile.isValid()) { // ~1.5 second flight max (30 blocks)
                    detonateImpact(currentLoc, player);
                    projectile.remove();
                    cancel();
                    return;
                }

                // Advance forward 1 block per tick
                currentLoc.add(direction.clone().multiply(1.1));
                projectile.teleport(currentLoc.clone().subtract(0, 0.7, 0));

                // Spin the hammer visually
                double angle = ticks * 0.8;
                projectile.setRightArmPose(new EulerAngle(angle, 0, 0));

                // Particle flair: High velocity electric rings + shockwaves
                world.spawnParticle(Particle.ELECTRIC_SPARK, currentLoc, 8, 0.3, 0.3, 0.3, 0.05);
                world.spawnParticle(Particle.SWEEP_ATTACK, currentLoc, 1, 0, 0, 0, 0);

                // Collision detection with non-solid blocks
                if (currentLoc.getBlock().getType().isSolid()) {
                    detonateImpact(currentLoc, player);
                    projectile.remove();
                    cancel();
                    return;
                }

                // Collision sweep for nearby living entities
                for (Entity entity : world.getNearbyEntities(currentLoc, 1.2, 1.2, 1.2)) {
                    if (entity instanceof LivingEntity target && !target.getUniqueId().equals(player.getUniqueId())) {
                        if (hitEntities.add(target.getUniqueId())) {
                            target.damage(14.0, player);
                            // Kinetic launch vector (away from blast line)
                            target.setVelocity(direction.clone().multiply(0.9).setY(0.4));
                            world.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 1.0f);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void detonateImpact(Location location, Player source) {
        World world = location.getWorld();
        if (world == null) return;

        // Visual flash & shockwave
        world.spawnParticle(Particle.FLASH, location, 2, 0, 0, 0, 0);
        world.spawnParticle(Particle.EXPLOSION, location, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.CLOUD, location, 25, 0.5, 0.2, 0.5, 0.1);

        // Stereo sound detonation
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.8f);
        world.playSound(location, Sound.ITEM_TRIDENT_THUNDER, 2.0f, 0.9f);

        // Strike true divine lightning
        world.strikeLightning(location);

        // Area-of-effect shockwave damage & launch
        for (Entity nearby : world.getNearbyEntities(location, 4.0, 3.0, 4.0)) {
            if (nearby instanceof LivingEntity target && !target.getUniqueId().equals(source.getUniqueId())) {
                target.damage(8.0, source);
                Vector knockup = target.getLocation().toVector().subtract(location.toVector()).normalize().setY(0.5);
                target.setVelocity(knockup.multiply(0.8));
            }
        }
    }

    /* =========================================================================
       COMMANDS & TAB COMPLETION
       ========================================================================= */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("godweapons.admin")) {
            sender.sendMessage(Component.text("You lack divine authority to wield this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("give") && args[1].equalsIgnoreCase("mjolnir")) {
            Player target = null;
            if (args.length >= 3) {
                target = Bukkit.getPlayer(args[2]);
            } else if (sender instanceof Player p) {
                target = p;
            }

            if (target == null) {
                sender.sendMessage(Component.text("Player not found or offline.", NamedTextColor.RED));
                return true;
            }

            target.getInventory().addItem(createMjolnir());
            target.sendMessage(Component.text("⚡ Mjölnir descends into your hands!", NamedTextColor.GOLD));
            target.playSound(target.getLocation(), Sound.ITEM_TOTEM_USE, 0.8f, 1.2f);
            sender.sendMessage(Component.text("Bestowed Mjölnir upon " + target.getName(), NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("Usage: /godweapons give mjolnir [player]", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("give");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return List.of("mjolnir");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return Collections.emptyList();
    }
}
