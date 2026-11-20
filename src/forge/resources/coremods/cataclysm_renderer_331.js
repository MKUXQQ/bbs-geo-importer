// Cataclysm 3.31 renamed the renderer; the model and Context constructor remain.
// Run before hierarchy resolution: a normal Mixin cannot load the missing parent.
function initializeCoreMod() {
    var oldName = "com/github/L_Ender/cataclysm/client/render/entity/Ancient_Remnant_Rework_Renderer";
    var newName = "com/github/L_Ender/cataclysm/client/render/entity/Ancient_Remnant_Renderer";
    function replace(value) { return value == null ? null : String(value).split(oldName).join(newName); }
    function patch(node) {
        if (String(node.superName) === oldName) node.superName = newName;
        node.signature = replace(node.signature);
        for (var i = 0; i < node.methods.size(); i++) {
            var method = node.methods.get(i);
            method.desc = replace(method.desc);
            method.signature = replace(method.signature);
            if (method.localVariables != null) for (var j = 0; j < method.localVariables.size(); j++) {
                var local = method.localVariables.get(j); local.desc = replace(local.desc); local.signature = replace(local.signature);
            }
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getType() === 5) { insn.owner = replace(insn.owner); insn.desc = replace(insn.desc); }
                else if (insn.getType() === 3) insn.desc = replace(insn.desc);
                else if (insn.getType() === 4) { insn.owner = replace(insn.owner); insn.desc = replace(insn.desc); }
                else if (insn.getType() === 14) {
                    var lists = [insn.local, insn.stack];
                    for (var k = 0; k < lists.length; k++) if (lists[k] != null) for (var n = 0; n < lists[k].size(); n++) {
                        var value = lists[k].get(n); if (String(value) === oldName) lists[k].set(n, newName);
                    }
                }
            }
        }
        return node;
    }
    return {
        "phantom_remnant_renderer": { target: {type:"CLASS", name:"net.acetheeldritchking.cataclysm_spellbooks.entity.render.mobs.PhantomAncientRemnantRenderer"}, transformer:patch },
        "phantom_remnant_layer": { target: {type:"CLASS", name:"net.acetheeldritchking.cataclysm_spellbooks.entity.render.mobs.AncientRemnantTransLayer"}, transformer:patch }
    };
}
