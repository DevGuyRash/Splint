package splint.animals;

import splint.core.isolate.Describers;
import zombie.characters.animals.IsoAnimal;

/** Names animals in core logs and commands (registered at module start). */
final class AnimalDescriber {
    private AnimalDescriber() {}

    static void register() {
        // Freezable: a broken animal can stand still for a while; the world does not need it.
        Describers.register(AnimalsModule.ID, IsoAnimal.class.getName(), true,
                o -> AnimalOps.describe((IsoAnimal) o), o -> AnimalOps.brief((IsoAnimal) o));
    }
}
