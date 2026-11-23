package splint.animals;

import splint.core.isolate.Describers;
import zombie.characters.animals.IsoAnimal;

/** Names animals in core logs and commands (registered at module start). */
final class AnimalDescriber {
    private AnimalDescriber() {}

    static void register() {
        Describers.register(AnimalsModule.ID, IsoAnimal.class.getName(),
                o -> AnimalOps.describe((IsoAnimal) o), o -> AnimalOps.brief((IsoAnimal) o));
    }
}
