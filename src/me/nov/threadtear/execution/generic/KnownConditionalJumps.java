package me.nov.threadtear.execution.generic;

import java.lang.reflect.Method;
import java.util.*;

import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import me.nov.threadtear.analysis.stack.*;
import me.nov.threadtear.execution.*;
import me.nov.threadtear.util.asm.Instructions;
import me.nov.threadtear.util.reflection.Casts;

public class KnownConditionalJumps extends Execution implements IConstantReferenceHandler {

  private int predictedJumps;
  private int predictedSwitches;

  public KnownConditionalJumps() {
    super(ExecutionCategory.GENERIC, "Remove obvious flow obfuscation", "Removes conditional jumps that are predictable.<br>This works for obfuscators like smoke or superblaubeere27.",
        ExecutionTag.POSSIBLE_VERIFY_ERR, ExecutionTag.BETTER_DECOMPILE, ExecutionTag.BETTER_DEOBFUSCATE);
  }

  @Override
  public boolean execute(Map<String, Clazz> classes, boolean verbose) {
    classes.values().stream().forEach(this::decrypt);
    logger.info("Removed " + predictedJumps + " unnecessary conditional jumps and " + predictedSwitches + " unnecessary switches.");
    return true;
  }

  public void decrypt(Clazz c) {
    ClassNode cn = c.node;
    logger.collectErrors(c);
    cn.methods.forEach(m -> {
      InsnList rewrittenCode = new InsnList();
      Map<LabelNode, LabelNode> labels = Instructions.cloneLabels(m.instructions);

      loopConstantFrames(cn, m, this, (ain, frame) -> {
        if (ain.getType() == AbstractInsnNode.JUMP_INSN) {
          try {
            int predicted = predictJump(frame, ain.getOpcode());
            if (predicted != 0) {
              rewrittenCode.add(new InsnNode(Math.abs(predicted) == 2 ? POP2 : POP));
              if (predicted > 0) {
                rewrittenCode.add(new JumpInsnNode(GOTO, labels.get(((JumpInsnNode) ain).label)));
              }
              predictedJumps++;
              return;
            }
          } catch (Exception e) {
            logger.error("Invalid stack in " + cn.name + "." + m.name + ":" + e.getMessage());
          }
        } else if (ain.getOpcode() == LOOKUPSWITCH) {
          LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) ain;
          if (frame.getStackSize() > 0) {
            ConstantValue up = frame.getStack(frame.getStackSize() - 1);
            if (up.isKnown() && up.isInteger()) {
              int input = up.getInteger();
              int index = lsin.keys.indexOf(input);
              rewrittenCode.add(new InsnNode(POP));
              rewrittenCode.add(new JumpInsnNode(GOTO, labels.get(index == -1 ? lsin.dflt : lsin.labels.get(index))));
              predictedSwitches++;
              return;
            }
          }
        } else if (ain.getOpcode() == TABLESWITCH) {
          TableSwitchInsnNode tsin = (TableSwitchInsnNode) ain;
          if (frame.getStackSize() > 0) {
            ConstantValue up = frame.getStack(frame.getStackSize() - 1);
            if (up.isKnown() && up.isInteger()) {
              int input = up.getInteger();
              int index = input - tsin.min;
              boolean dflt = index < 0 || index > tsin.max;
              rewrittenCode.add(new InsnNode(POP));
              rewrittenCode.add(new JumpInsnNode(GOTO, labels.get(dflt ? tsin.dflt : tsin.labels.get(index))));
              predictedSwitches++;
              return;
            }
          }
        }
        rewrittenCode.add(ain.clone(labels));
      });
      if (rewrittenCode.size() > 0) {
        Instructions.updateInstructions(m, labels, rewrittenCode);
        Instructions.removeDeadCode(cn, m);
      }
    });
  }

  private int predictJump(Frame<ConstantValue> frame, int op) {
    if (frame.getStackSize() == 0)
      return 0;
    ConstantValue up = frame.getStack(frame.getStackSize() - 1);
    if (!up.isKnown())
      return 0;
    Object upperVal = up.getValue();
    switch (op) {
    case IFEQ:
      return Casts.toInteger(upperVal) == 0 ? 1 : -1;
    case IFNE:
      return Casts.toInteger(upperVal) != 0 ? 1 : -1;
    case IFNULL:
      return upperVal.equals(ConstantTracker.NULL) ? 1 : -1;
    case IFNONNULL:
      return !upperVal.equals(ConstantTracker.NULL) ? 1 : -1;
    case IFGT:
      return Casts.toInteger(upperVal) > 0 ? 1 : -1;
    case IFGE:
      return Casts.toInteger(upperVal) >= 0 ? 1 : -1;
    case IFLT:
      return Casts.toInteger(upperVal) < 0 ? 1 : -1;
    case IFLE:
      return Casts.toInteger(upperVal) <= 0 ? 1 : -1;
    }
    if (frame.getStackSize() >= 2) {
      ConstantValue low = frame.getStack(frame.getStackSize() - 2);
      if (!low.isKnown())
        return 0;
      Object lowerVal = low.getValue();
      switch (op) {
      case IF_ICMPEQ:
        return ((Integer) upperVal).equals(lowerVal) ? 2 : -2;
      case IF_ICMPNE:
        return ((Integer) upperVal).equals(lowerVal) ? -2 : 2;
      case IF_ICMPLT:
        return Casts.toInteger(lowerVal) < Casts.toInteger(upperVal) ? 2 : -2;
      case IF_ICMPGE:
        return Casts.toInteger(lowerVal) >= Casts.toInteger(upperVal) ? 2 : -2;
      case IF_ICMPGT:
        return Casts.toInteger(lowerVal) > Casts.toInteger(upperVal) ? 2 : -2;
      case IF_ICMPLE:
        return Casts.toInteger(lowerVal) <= Casts.toInteger(upperVal) ? 2 : -2;
      case IF_ACMPEQ:
        return up.equals(low) ? 2 : -2;
      case IF_ACMPNE:
        return !up.equals(low) ? 2 : -2;
      }
    }
    return 0;

  }

  @Override
  public Object getFieldValueOrNull(BasicValue v, String owner, String name, String desc) {
    return null;
  }

  @Override
  public Object getMethodReturnOrNull(BasicValue v, String owner, String name, String desc, List<? extends ConstantValue> values) {
    if (owner.equals("java/lang/String") && desc.startsWith("()")) {
      if (values.size() != 1 || !values.get(0).isKnown()) {
        return null;
      }
      // this can be improved by also simulating methods with arguments, but i'm to
      // lazy to implement that
      // TODO maybe even implement fake getters that just return an int value that are
      // used to hide gotos
      try {
        Method method = String.class.getDeclaredMethod(name);
        return method.invoke(values.get(0).getValue());
      } catch (Throwable t) {
        logger.error("Throwable", t);
      }
    }
    return null;
  }
}