package khi.pentecost.mixin;

import khi.pentecost.boot.Log;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

final class MixinLog extends LoggerAdapterAbstract {
    MixinLog(String name) {
        super(name);
    }

    @Override
    public String getType() {
        return "Pentecost";
    }

    @Override
    public void catching(Level level, Throwable t) {
        log(level, "Caught " + t, t);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        if (level.compareTo(Level.WARN) > 0) {
            return;
        }
        FormattedMessage f = new FormattedMessage(message, params);
        emit(level, f.getMessage(), f.getThrowable());
    }

    @Override
    public void log(Level level, String message, Throwable t) {
        if (level.compareTo(Level.WARN) > 0) {
            return;
        }
        emit(level, message, t);
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        log(Level.WARN, "Throwing " + t, t);
        return t;
    }

    private void emit(Level level, String message, Throwable t) {
        String line = "[Mixin] " + message + (t == null ? "" : " : " + t);
        if (level == Level.WARN) {
            Log.warn(line);
        } else {
            Log.error(line);
        }
    }
}
