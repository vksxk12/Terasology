/*
 * Copyright 2013 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.logic.characters;

import org.terasology.entitySystem.EntityRef;

/**
 * A CharacterMover is responsible for calculating character movement and generating related events.
 * The events that can be generated by movement include:
 * <ul>
 * <li> OnEnterLiquidEvent </li>
 * <li> OnLeaveLiquidEvent </li>
 * <li> MovedEvent </li>
 * <li> VerticalCollisionEvent </li>
 * <li> FootstepEvent </li>
 * <li> SwimStrokeEvent </li>
 * <li> JumpEvent </li>
 * <li> HorizontalCollisionEvent </li>
 * </ul>
 *
 * @author Immortius
 */
public interface CharacterMover {

    /**
     * Calculate the new state of a character based on the previous state and
     * the input. Note that input is not always player input. Input can also be
     * for an AI character.
     *
     * @param initial The initial state to start from
     * @param input   The input driving the movement change
     * @param entity  The character
     * @return The new state of the character
     */
    CharacterStateEvent step(CharacterStateEvent initial, CharacterMoveInputEvent input, EntityRef entity);

}
