<?php

declare(strict_types=1);

namespace Yoon\Symfony;

use Symfony\Component\Config\Definition\Configurator\DefinitionConfigurator;
use Symfony\Component\DependencyInjection\ContainerBuilder;
use Symfony\Component\DependencyInjection\Loader\Configurator\ContainerConfigurator;
use Symfony\Component\DependencyInjection\Reference;
use Symfony\Component\HttpKernel\Bundle\AbstractBundle;
use Symfony\Component\HttpKernel\KernelEvents;
use Yoon\Symfony\EventListener\YoonWebhookListener;
use Yoon\Symfony\ValueResolver\YoonEventValueResolver;
use Yoon\Yoon;

/**
 * Symfony integration (6.4 LTS and 7.x). Register it in config/bundles.php and configure
 * config/packages/yoon.yaml:
 *
 *     yoon:
 *         url: '%env(YOON_URL)%'
 *         api_key: '%env(YOON_API_KEY)%'
 *         webhook_secret: '%env(YOON_WEBHOOK_SECRET)%'
 *
 * Gives an autowirable {@see Yoon} service and the {@see Attribute\YoonWebhook} attribute.
 */
final class YoonBundle extends AbstractBundle
{
    protected string $extensionAlias = 'yoon';

    public function getPath(): string
    {
        return __DIR__;
    }

    public function configure(DefinitionConfigurator $definition): void
    {
        $definition->rootNode()
            ->children()
                ->scalarNode('url')->isRequired()->cannotBeEmpty()
                    ->info('Where your Yoon instance runs, e.g. https://pay.example.com')->end()
                ->scalarNode('api_key')->isRequired()->cannotBeEmpty()
                    ->info('This application\'s API key (yk_…), from `yoon apps create <name>`')->end()
                ->scalarNode('webhook_secret')->defaultNull()
                    ->info('The secret Yoon signs webhooks with (YOON_APPS_<APP>_WEBHOOK_SECRET on the Yoon side)')->end()
                ->floatNode('timeout')->defaultValue(30.0)->min(0)->info('Seconds; the connect timeout is 5 s')->end()
                ->arrayNode('webhook')->addDefaultsIfNotSet()
                    ->children()
                        ->scalarNode('cache')->defaultValue('cache.app')
                            ->info('PSR-6 pool remembering handled event ids')->end()
                        ->integerNode('dedupe_seconds')->defaultValue(259200)->min(1)
                            ->info('How long a handled event id is remembered (default 3 days)')->end()
                    ->end()
                ->end()
            ->end();
    }

    /** @param array<string, mixed> $config */
    public function loadExtension(array $config, ContainerConfigurator $container, ContainerBuilder $builder): void
    {
        $services = $container->services();

        $services->set(Yoon::class)
            ->args([$config['url'], $config['api_key'], null, $config['timeout']]);
        $services->alias('yoon', Yoon::class)->public();

        $services->set(YoonWebhookListener::class)
            ->args([$config['webhook_secret'], new Reference($config['webhook']['cache']), $config['webhook']['dedupe_seconds']])
            ->tag('kernel.event_listener', ['event' => KernelEvents::CONTROLLER, 'method' => 'onController'])
            ->tag('kernel.event_listener', ['event' => KernelEvents::RESPONSE, 'method' => 'onResponse']);

        $services->set(YoonEventValueResolver::class)
            ->tag('controller.argument_value_resolver', ['priority' => 150]);
    }
}
