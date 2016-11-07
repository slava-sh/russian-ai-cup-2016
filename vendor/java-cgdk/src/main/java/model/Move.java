package model;

import java.util.Arrays;

/**
 * Стратегия игрока может управлять волшебником посредством установки свойств объекта данного класса.
 */
public class Move {
    private double speed;
    private double strafeSpeed;
    private double turn;
    private ActionType action;
    private double castAngle;
    private double minCastDistance;
    private double maxCastDistance = 10000.0D;
    private long statusTargetId = -1L;
    private SkillType skillToLearn;
    private Message[] messages;

    /**
     * @return Возвращает текущую установку скорости перемещения.
     */
    public double getSpeed() {
        return speed;
    }

    /**
     * Задаёт установку скорости перемещения на один тик.
     * <p>
     * Установка скорости перемещения по умолчанию лежит в интервале от {@code -game.wizardBackwardSpeed} до
     * {@code game.wizardForwardSpeed}, однако границы интервала могут быть расширены в зависимости от изученных
     * волшебником умений, от действия некоторых аур, а также в случае действия статуса {@code HASTENED}.
     * <p>
     * Значения, выходящие за интервал, будут приведены к ближайшей его границе.
     * Положительные значения соответствуют движению вперёд.
     * <p>
     * Если {@code hypot(speed / maxSpeed, strafeSpeed / maxStrafeSpeed)} больше {@code 1.0}, то обе установки скорости
     * перемещения ({@code speed} и {@code strafeSpeed}) будут поделены игровым симулятором на это значение.
     */
    public void setSpeed(double speed) {
        this.speed = speed;
    }

    /**
     * @return Возвращает текущую установку скорости перемещения боком.
     */
    public double getStrafeSpeed() {
        return strafeSpeed;
    }

    /**
     * Задаёт установку скорости перемещения боком на один тик.
     * <p>
     * Установка скорости перемещения по умолчанию лежит в интервале от {@code -game.wizardStrafeSpeed} до
     * {@code game.wizardStrafeSpeed}, однако границы интервала могут быть расширены в зависимости от изученных
     * волшебником умений, от действия некоторых аур, а также в случае действия статуса {@code HASTENED}.
     * <p>
     * Значения, выходящие за интервал, будут приведены к ближайшей его границе.
     * Положительные значения соответствуют движению направо.
     * <p>
     * Если {@code hypot(speed / maxSpeed, strafeSpeed / maxStrafeSpeed)} больше {@code 1.0}, то обе установки скорости
     * перемещения ({@code speed} и {@code strafeSpeed}) будут поделены игровым симулятором на это значение.
     */
    public void setStrafeSpeed(double strafeSpeed) {
        this.strafeSpeed = strafeSpeed;
    }

    /**
     * @return Возвращает текущий угол поворота волшебника.
     */
    public double getTurn() {
        return turn;
    }

    /**
     * Устанавливает угол поворота волшебника.
     * <p/>
     * Угол поворота задаётся в радианах относительно текущего направления волшебника и обычно ограничен интервалом от
     * {@code -game.wizardMaxTurnAngle} до {@code game.wizardMaxTurnAngle}. Если на волшебника действует магический
     * статус {@code HASTENED}, то нижнюю и правую границу интервала необходимо умножить на
     * {@code 1.0 + game.hastenedRotationBonusFactor}.
     * <p>
     * Значения, выходящие за интервал, будут приведены к ближайшей его границе.
     * Положительные значения соответствуют повороту по часовой стрелке.
     */
    public void setTurn(double turn) {
        this.turn = turn;
    }

    /**
     * @return Возвращает текущее действие волшебника.
     */
    public ActionType getAction() {
        return action;
    }

    /**
     * Устанавливает действие волшебника.
     * <p>
     * Действие может быть проигнорировано игровым симулятором, если у волшебника недостаточно магической энергии для
     * его совершения и/или волшебник ещё не успел восстановиться после предыдущего действия.
     */
    public void setAction(ActionType action) {
        this.action = action;
    }

    /**
     * @return Возвращает текущий угол полёта магического снаряда.
     */
    public double getCastAngle() {
        return castAngle;
    }

    /**
     * Устанавливает угол полёта магического снаряда.
     * <p>
     * Угол полёта задаётся в радианах относительно текущего направления волшебника и ограничен интервалом от
     * {@code -game.staffSector / 2.0} до {@code game.staffSector / 2.0}.
     * <p>
     * Значения, выходящие за интервал, будут приведены к ближайшей его границе.
     * Положительные значения соответствуют повороту по часовой стрелке.
     * <p>
     * Параметр будет проигнорирован игровым симулятором, если действие волшебника не связано с созданием магического
     * снаряда.
     */
    public void setCastAngle(double castAngle) {
        this.castAngle = castAngle;
    }

    /**
     * Возвращает текущую установку для ближней границы боевого применения магического снаряда.
     */
    public double getMinCastDistance() {
        return minCastDistance;
    }

    /**
     * Устанавливает ближнюю границу боевого применения магического снаряда.
     * <p>
     * Если расстояние от центра снаряда до точки его появления меньше, чем значение данного параметра, то боевые
     * свойства снаряда игнорируются. Снаряд беспрепятственно проходит сквозь все другие игровые объекты, за исключением
     * деревьев.
     * <p>
     * Значение параметра по умолчанию равно {@code 0.0}. Столкновения снаряда и юнита, который его создал,
     * игнорируются.
     * <p>
     * Параметр будет проигнорирован игровым симулятором, если действие волшебника не связано с созданием магического
     * снаряда.
     */
    public void setMinCastDistance(double minCastDistance) {
        this.minCastDistance = minCastDistance;
    }

    /**
     * Возвращает текущую установку для дальней границы боевого применения магического снаряда.
     */
    public double getMaxCastDistance() {
        return maxCastDistance;
    }

    /**
     * Устанавливает дальнюю границу боевого применения магического снаряда.
     * <p>
     * Если расстояние от центра снаряда до точки его появления больше, чем значение данного параметра, то снаряд
     * убирается из игрового мира. При этом, снаряд типа {@code FIREBALL} детонирует.
     * <p>
     * Значение параметра по умолчанию заведомо выше максимальной дальности полёта любого типа снарядов в игре.
     * <p>
     * Параметр будет проигнорирован игровым симулятором, если действие волшебника не связано с созданием магического
     * снаряда.
     */
    public void setMaxCastDistance(double maxCastDistance) {
        this.maxCastDistance = maxCastDistance;
    }

    /**
     * @return Возвращает идентификатор текущей цели для применения магического статуса.
     */
    public long getStatusTargetId() {
        return statusTargetId;
    }

    /**
     * Устанавливает идентификатор цели для применения магического статуса.
     * <p>
     * Допустимыми целями являются только волшебники дружественной фракции. Если волшебник с указанным идентификатором
     * не найден, то статус применяется непосредственно к волшебнику, совершающему действие. Относительный угол до цели
     * должен лежать в интервале от {@code -game.staffSector / 2.0} до {@code game.staffSector / 2.0}, а максимальная
     * дистанция ограничена дальностью полёта магического снаряда этого волшебника. Её базовое значение равно
     * {@code game.wizardCastRange}, однако оно может быть увеличено после изучения некоторых умений.
     * <p>
     * Значение параметра по умолчанию равно {@code -1}.
     * <p>
     * Параметр будет проигнорирован игровым симулятором, если действие волшебника не связано с применением магического
     * статуса.
     */
    public void setStatusTargetId(long statusTargetId) {
        this.statusTargetId = statusTargetId;
    }

    /**
     * @return Возвращает выбранное для изучения умение.
     */
    public SkillType getSkillToLearn() {
        return skillToLearn;
    }

    /**
     * Задаёт установку изучить указанное умение до начала следующего игрового тика.
     * <p>
     * Установка будет проигнорирована игровым симулятором, если текущий уровень волшебника меньше либо равен количеству
     * уже изученных умений. Некоторые умения также могут требовать предварительного изучения других умений.
     * <p>
     * Изучение умений доступно не во всех режимах игры.
     */
    public void setSkillToLearn(SkillType skillToLearn) {
        this.skillToLearn = skillToLearn;
    }

    /**
     * @return Возвращает текущие сообщения для волшебников дружественной фракции.
     */
    public Message[] getMessages() {
        return messages == null ? null : Arrays.copyOf(messages, messages.length);
    }

    /**
     * Устанавливает сообщения для волшебников дружественной фракции.
     * <p>
     * Доступно для использования только верховному волшебнику ({@code wizard.master}). Если используется, количество
     * сообщений должно быть строго равно количеству волшебников дружественной фракции (живых или ожидающих возрождения)
     * за исключением самого верховного волшебника. Нарушение данных условий может привести к игнорированию параметра
     * игровым симулятором или даже к обрыву соединения со стратегией участника.
     * <p>
     * Сообщения адресуются в порядке возрастания идентификаторов волшебников. Отдельные сообщения могут быть пустыми
     * (равны {@code null}), если это поддерживается языком программирования, который использует стратегия. В противном
     * случае все элементы должны быть корректными сообщениями.
     * <p>
     * Игровой симулятор вправе проигнорировать сообщение конкретному волшебнику, если для него в системе уже
     * зарегистрировано и ещё им не получено другое сообщение. Если в тик получения сообщения волшебник мёртв, то
     * данное сообщение будет удалено из игрового мира и волшебник никогда его не получит.
     * <p>
     * Отправка сообщений доступна не во всех режимах игры.
     */
    public void setMessages(Message[] messages) {
        this.messages = messages == null ? null : Arrays.copyOf(messages, messages.length);
    }
}
