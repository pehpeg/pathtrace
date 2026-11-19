package com.pathtrace.client;

import com.pathtrace.PTConfig;
import com.pathtrace.PathTraceMod;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side chat command:
 * /pathtrace on | off | toggle | status | reload
 * /pathtrace set <setting> <value>     (any option from the config; saved)
 * /pathtrace get <setting>
 */
final class PathTraceCommand extends CommandBase {

    @Override
    public String getName() {
        return "pathtrace";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("pt");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/pathtrace <on|off|toggle|status|reload|perf|preset <potato|fast|balanced|quality|ultra>|set <setting> <value>|get <setting>>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    private static void say(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString("§6[PathTrace]§r " + msg));
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getUsage(sender));
        switch (args[0].toLowerCase()) {
            case "on":
                ClientHandler.setEnabled(true);
                say(sender, "§aon");
                break;
            case "off":
                ClientHandler.setEnabled(false);
                say(sender, "§coff");
                break;
            case "toggle":
                ClientHandler.setEnabled(!ClientHandler.isEnabled());
                say(sender, ClientHandler.isEnabled() ? "§aon" : "§coff");
                break;
            case "status":
                say(sender, (ClientHandler.isEnabled() ? "§aon" : "§coff") + "§r, version " + PathTraceMod.VERSION);
                break;
            case "perf":
                if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
                    ClientHandler.perfReset();
                    say(sender, "timings reset");
                    break;
                }
                for (String line : ClientHandler.perfReport().split("\n")) say(sender, line);
                say(sender, "§7(averaged over the last ~20 frames; the 'vanilla' entry is Minecraft's own work between our passes)");
                break;
            case "preset": {
                if (args.length < 2) throw new WrongUsageException("/pathtrace preset <potato|fast|balanced|quality|ultra>");
                String p = args[1].toLowerCase();
                switch (p) {
                    case "potato":   preset(0.25, 0.25, 1, 1, 1); break;
                    case "fast":     preset(0.33, 0.33, 1, 1, 1); break;
                    case "balanced": preset(0.5, 0.5, 3, 1, 2); break;
                    case "quality":  preset(0.5, 1.0, 4, 1, 2); break;
                    case "ultra":    preset(0.75, 1.0, 4, 2, 2); break;
                    default: throw new WrongUsageException("/pathtrace preset <potato|fast|balanced|quality|ultra>");
                }
                ConfigManager.sync(PathTraceMod.MODID, Config.Type.INSTANCE);
                ClientHandler.resetHistory();
                say(sender, "preset " + p + ": renderScale " + PTConfig.renderScale + ", directScale " + PTConfig.directScale
                        + ", shadowedLights " + PTConfig.shadowedLights + ", bounces " + PTConfig.bounces);
                break;
            }
            case "reload":
                ClientHandler.reloadShaders();
                say(sender, "shaders reloaded");
                break;
            case "get": {
                if (args.length < 2) throw new WrongUsageException("/pathtrace get <setting>");
                Field f = field(args[1]);
                try {
                    say(sender, f.getName() + " = " + f.get(null));
                } catch (IllegalAccessException e) {
                    throw new CommandException("can't read " + f.getName());
                }
                break;
            }
            case "set": {
                if (args.length < 3) throw new WrongUsageException("/pathtrace set <setting> <value>");
                Field f = field(args[1]);
                try {
                    Class<?> t = f.getType();
                    if (t == boolean.class) {
                        String v = args[2].toLowerCase();
                        if (!v.equals("true") && !v.equals("false") && !v.equals("on") && !v.equals("off"))
                            throw new CommandException("expected true/false");
                        f.setBoolean(null, v.equals("true") || v.equals("on"));
                    } else if (t == int.class) {
                        int v = parseInt(args[2]);
                        Config.RangeInt r = f.getAnnotation(Config.RangeInt.class);
                        if (r != null) v = Math.max(r.min(), Math.min(r.max(), v));
                        f.setInt(null, v);
                    } else if (t == double.class) {
                        double v = parseDouble(args[2]);
                        Config.RangeDouble r = f.getAnnotation(Config.RangeDouble.class);
                        if (r != null) v = Math.max(r.min(), Math.min(r.max(), v));
                        f.setDouble(null, v);
                    } else {
                        throw new CommandException("can't set " + f.getName());
                    }
                    ConfigManager.sync(PathTraceMod.MODID, Config.Type.INSTANCE);   // save to the config file
                    ClientHandler.resetHistory();
                    say(sender, f.getName() + " = " + f.get(null));
                } catch (IllegalAccessException e) {
                    throw new CommandException("can't set " + f.getName());
                }
                break;
            }
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    private static void preset(double renderScale, double directScale, int shadowed, int bounces, int samples) {
        PTConfig.lightSamples = samples;
        PTConfig.renderScale = renderScale;
        PTConfig.directScale = directScale;
        PTConfig.shadowedLights = shadowed;
        PTConfig.bounces = bounces;
    }

    private static List<String> settingNames() {
        List<String> names = new ArrayList<>();
        for (Field f : PTConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) names.add(f.getName());
        }
        return names;
    }

    private static Field field(String name) throws CommandException {
        for (Field f : PTConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().equalsIgnoreCase(name)) return f;
        }
        throw new CommandException("unknown setting '" + name + "'. Settings: " + String.join(", ", settingNames()));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "on", "off", "toggle", "status", "reload", "set", "get", "perf", "preset");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("preset")) {
            return getListOfStringsMatchingLastWord(args, "potato", "fast", "balanced", "quality", "ultra");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("get"))) {
            return getListOfStringsMatchingLastWord(args, settingNames());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            try {
                if (field(args[1]).getType() == boolean.class) return getListOfStringsMatchingLastWord(args, "true", "false");
            } catch (CommandException ignored) {
            }
        }
        return Collections.emptyList();
    }
}
